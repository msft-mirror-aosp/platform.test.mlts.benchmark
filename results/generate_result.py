#!/usr/bin/python3
#
# Copyright 2018, The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""MLTS benchmark result generator.

Reads a CSV produced by MLTS benchmark and generates
an HTML page with results summary.

Usage:
  generate_result [csv input file] [html output file]
"""

import argparse
import collections
import csv
import os
import re


class ScoreException(Exception):
  """Generator base exception type. """
  pass


BenchmarkResult = collections.namedtuple(
    'BenchmarkResult',
    ['name', 'backend_type', 'iterations', 'total_time_sec', 'max_single_error',
     'testset_size', 'evaluator_keys', 'evaluator_values',
     'time_freq_start_sec', 'time_freq_step_sec', 'time_freq_sec'])


ResultsWithBaseline = collections.namedtuple(
    'ResultsWithBaseline',
    ['baseline', 'other'])


BASELINE_BACKEND = 'TFLite_CPU'
KNOWN_GROUPS = [(re.compile('mobilenet_v1.*quant.*'), 'mobilenet_v1_quant'),
                (re.compile('mobilenet_v1.*'), 'mobilenet_v1_float'),
                (re.compile('tts.*'), 'tts')]


def parse_csv_input(input_filename):
  """Parse input CSV file, returns: (benchmarkInfo, list of BenchmarkResult)."""
  with open(input_filename, 'r') as csvfile:
    csv_reader = csv.reader(filter(lambda row: row[0] != '#', csvfile))

    # First line contain device info
    benchmark_info = next(csv_reader)

    results = [BenchmarkResult(
        name=row[0],
        backend_type=row[1],
        iterations=int(row[2]),
        total_time_sec=float(row[3]),
        max_single_error=float(row[4]),
        testset_size=int(row[5]),
        time_freq_start_sec=float(row[7]),
        time_freq_step_sec=float(row[8]),
        evaluator_keys=row[9:9 + int(row[6])*2:2],
        evaluator_values=row[10: 9 + int(row[6])*2:2],
        time_freq_sec=[float(x) for x in row[10 + int(row[6])*2:]])
               for row in csv_reader]
    return (benchmark_info, results)


def group_results(results):
  """Group list of results by their name/backend, returns list of lists."""
  # Group by name
  groupings = collections.defaultdict(list)
  for result in results:
    groupings[result.name].append(result)

  # Find baseline for each group, make ResultsWithBaseline for each name
  groupings_baseline = {}
  for name, results in groupings.items():
    baseline = next(filter(lambda x: x.backend_type == BASELINE_BACKEND,
                           results))
    other = sorted(filter(lambda x: x is not baseline, results),
                   key=lambda x: x.backend_type)
    groupings_baseline[name] = ResultsWithBaseline(
        baseline=baseline,
        other=other)

  # Merge ResultsWithBaseline for known groups
  known_groupings_baseline = collections.defaultdict(list)
  for name, result_with_bl in sorted(groupings_baseline.items()):
    group_name = name
    for known_group in KNOWN_GROUPS:
      if known_group[0].match(result_with_bl.baseline.name):
        group_name = known_group[1]
        break
    known_groupings_baseline[group_name].append(result_with_bl)

  # Turn into a list sorted by name
  groupings_list = []
  for name, results_wbl in sorted(known_groupings_baseline.items()):
    groupings_list.append(results_wbl)
  return groupings_list


def get_frequency_graph(time_freq_start_sec, time_freq_step_sec, time_freq_sec):
  """Generate input x/y data for latency frequency graph."""
  return (['{:.2f}ms'.format(
      (time_freq_start_sec + x * time_freq_step_sec) * 1000.0)
           for x in range(len(time_freq_sec))], time_freq_sec)


def is_topk_evaluator(evaluator_keys):
  """Are these evaluator keys from TopK evaluator?"""
  return (len(evaluator_keys) == 5 and
          evaluator_keys[0] == 'top_1' and
          evaluator_keys[1] == 'top_2' and
          evaluator_keys[2] == 'top_3' and
          evaluator_keys[3] == 'top_4' and
          evaluator_keys[4] == 'top_5')


def is_melceplogf0_evaluator(evaluator_keys):
  """Are these evaluator keys from MelCepLogF0 evaluator?"""
  return (len(evaluator_keys) == 2 and
          evaluator_keys[0] == 'max_mel_cep_distortion' and
          evaluator_keys[1] == 'max_log_f0_error')


def generate_accuracy_headers(result):
  """Accuracy-related headers for result table."""
  if is_topk_evaluator(result.evaluator_keys):
    return ACCURACY_HEADERS_TOPK_TEMPLATE
  elif is_melceplogf0_evaluator(result.evaluator_keys):
    return ACCURACY_HEADERS_MELCEPLOGF0_TEMPLATE
  elif result.evaluator_keys:
    return ACCURACY_HEADERS_BASIC_TEMPLATE
  raise ScoreException('Unknown accuracy headers for: ' + str(result))


def get_diff_span(value, same_delta, positive_is_better):
  if abs(value) < same_delta:
    return 'same'
  if positive_is_better and value > 0 or not positive_is_better and value < 0:
    return 'better'
  return 'worse'


def generate_accuracy_values(baseline, result):
  """Accuracy-related data for result table."""
  if is_topk_evaluator(result.evaluator_keys):
    val = [float(x) * 100.0 for x in result.evaluator_values]
    if result is baseline:
      topk = [TOPK_BASELINE_TEMPLATE.format(val=x) for x in val]
      return ACCURACY_VALUES_TOPK_TEMPLATE.format(
          top1=topk[0], top2=topk[1], top3=topk[2], top4=topk[3],
          top5=topk[4]
      )
    else:
      base = [float(x) * 100.0 for x in baseline.evaluator_values]
      diff = [a - b for a, b in zip(val, base)]
      topk = [TOPK_DIFF_TEMPLATE.format(
          val=v, diff=d,span=get_diff_span(d, 1.0, positive_is_better=True))
              for v, d in zip(val, diff)]
      return ACCURACY_VALUES_TOPK_TEMPLATE.format(
          top1=topk[0], top2=topk[1], top3=topk[2], top4=topk[3],
          top5=topk[4]
      )
  elif is_melceplogf0_evaluator(result.evaluator_keys):
    val = [float(x) for x in result.evaluator_values + [result.max_single_error]]
    if result is baseline:
      return ACCURACY_VALUES_MELCEPLOGF0_TEMPLATE.format(
        max_log_f0=MELCEPLOGF0_BASELINE_TEMPLATE.format(val=val[0]),
        max_mel_cep_distortion=MELCEPLOGF0_BASELINE_TEMPLATE.format(val=val[1]),
        max_single_error=MELCEPLOGF0_BASELINE_TEMPLATE.format(val=val[2]),
      )
    else:
      base = [float(x) for x in baseline.evaluator_values + [baseline.max_single_error]]
      diff = [a - b for a, b in zip(val, base)]
      v = [MELCEPLOGF0_DIFF_TEMPLATE.format(
          val=v, diff=d, span=get_diff_span(d, 1.0, positive_is_better=False))
              for v, d in zip(val, diff)]
      return ACCURACY_VALUES_MELCEPLOGF0_TEMPLATE.format(
        max_log_f0=v[0],
        max_mel_cep_distortion=v[1],
        max_single_error=v[2],
      )
    return ACCURACY_VALUES_MELCEPLOGF0_TEMPLATE.format(
        max_log_f0=float(result.evaluator_values[0]),
        max_mel_cep_distortion=float(result.evaluator_values[1]),
        max_single_error=float(result.max_single_error),
        )
  elif result.evaluator_keys:
    return ACCURACY_VALUES_BASIC_TEMPLATE.format(
        max_single_error=result.max_single_error,
        )
  raise ScoreException('Unknown accuracy values for: ' + str(result))


def getchartjs_source():
  return open(os.path.dirname(os.path.abspath(__file__)) + '/' +
              CHART_JS_FILE).read()


def generate_avg_ms(baseline, result):
  """Generate average latency value."""
  if result is None:
    result = baseline

  result_avg_ms = (result.total_time_sec / result.iterations)*1000.0
  if result is baseline:
    return LATENCY_BASELINE_TEMPLATE.format(val=result_avg_ms)
  baseline_avg_ms = (baseline.total_time_sec / baseline.iterations)*1000.0
  diff = (result_avg_ms/baseline_avg_ms - 1.0) * 100.0
  diff_val = result_avg_ms - baseline_avg_ms
  return LATENCY_DIFF_TEMPLATE.format(
      val=result_avg_ms,
      diff=diff,
      diff_val=diff_val,
      span=get_diff_span(diff, same_delta=1.0, positive_is_better=False))


def generate_result_entry(baseline, result):
  if result is None:
    result = baseline

  return RESULT_ENTRY_TEMPLATE.format(
      row_class='baseline' if result is baseline else 'normal',
      name=result.name,
      backend=result.backend_type,
      i=id(result),
      iterations=result.iterations,
      testset_size=result.testset_size,
      accuracy_values=generate_accuracy_values(baseline, result),
      avg_ms=generate_avg_ms(baseline, result),
      freq_data=get_frequency_graph(result.time_freq_start_sec,
                                    result.time_freq_step_sec,
                                    result.time_freq_sec))


def generate_result(benchmark_info, data):
  """Turn list of results into HTML."""
  return MAIN_TEMPLATE.format(
      jsdeps=getchartjs_source(),
      device_info=DEVICE_INFO_TEMPLATE.format(
          benchmark_time=benchmark_info[0],
          device_info=benchmark_info[1],
          ),
      results_list=''.join((
          RESULT_GROUP_TEMPLATE.format(
              accuracy_headers=generate_accuracy_headers(
                  entries_group[0].baseline),
              results=''.join((
                  RESULT_ENTRY_WITH_BASELINE_TEMPLATE.format(
                      baseline=generate_result_entry(
                          result_and_bl.baseline, None),
                      other=''.join(
                          generate_result_entry(
                              result_and_bl.baseline, x)
                          for x in result_and_bl.other)
                  ) for i, result_and_bl in enumerate(entries_group))
                             )
          ) for entries_group in group_results(data))
                          ))


def main():
  parser = argparse.ArgumentParser()
  parser.add_argument('input', help='input csv filename')
  parser.add_argument('output', help='output html filename')
  args = parser.parse_args()

  benchmark_info, data = parse_csv_input(args.input)

  with open(args.output, 'w') as htmlfile:
    htmlfile.write(generate_result(benchmark_info, data))


# -----------------
# Templates below

MAIN_TEMPLATE = """<!doctype html>
<html lang='en-US'>
<head>
  <meta http-equiv='Content-Type' content='text/html; charset=utf-8'>
  <script src='https://ajax.googleapis.com/ajax/libs/jquery/3.3.1/jquery.min.js'></script>
  <script>{jsdeps}</script>
  <title>MLTS results</title>
  <style>
    .results {{
      border-collapse: collapse;
      width: 100%;
    }}
    .results td, .results th {{
      border: 1px solid #ddd;
      padding: 6px;
    }}
    .results tbody {{
      border-top: 8px solid #666;
      border-bottom: 8px solid #666;
    }}
    span.better {{
      color: #070;
    }}
    span.worse {{
      color: #700;
    }}
    span.same {{
      color: #000;
    }}
    .results tr:nth-child(even) {{background-color: #eee;}}
    .results tr:hover {{background-color: #ddd;}}
    .results th {{
      padding: 10px;
      font-weight: bold;
      text-align: left;
      background-color: #333;
      color: white;
    }}
  </style>
</head>
<body>
{device_info}
{results_list}
</body>
</html>"""

DEVICE_INFO_TEMPLATE = """<div id='device_info'>
Benchmark for {device_info}, started at {benchmark_time}
</div>"""


RESULT_GROUP_TEMPLATE = """<div>
<table class='results'>
 <tr>
   <th>Name</th>
   <th>Backend</th>
   <th>Iterations</th>
   <th>Test set size</th>
   <th>Average latency ms</th>
   {accuracy_headers}
   <th>Latency frequency</th>
 </tr>
 {results}
</table>
</div>"""


RESULT_ENTRY_WITH_BASELINE_TEMPLATE = """
 <tbody>
 {baseline}
 {other}
 </tbody>
"""

RESULT_ENTRY_TEMPLATE = """
  <tr class={row_class}>
   <td>{name}</td>
   <td>{backend}</td>
   <td>{iterations:d}</td>
   <td>{testset_size:d}</td>
   <td>{avg_ms}</td>
   {accuracy_values}
   <td class='container' style='width: 200px;'>
    <canvas id='latency_chart{i}' class='latency_chart'></canvas>
   </td>
  <script>
   $(function() {{
       var freqData = {{
         labels: {freq_data[0]},
         datasets: [{{
            label: '{name} latency frequency',
            data: {freq_data[1]},
            backgroundColor: 'rgba(255, 99, 132, 0.6)',
            borderColor:  'rgba(255, 0, 0, 0.6)',
            borderWidth: 1,
         }}]
       }};
       var ctx = $('#latency_chart{i}')[0].getContext('2d');
       window.latency_chart{i} = new Chart(ctx,
        {{
          type: 'bar',
          data: freqData,
          options: {{
           responsive: true,
           title: {{
             display: false,
             text: 'Latency frequency'
           }},
           legend: {{
             display: false
           }},
           scales: {{
            xAxes: [ {{
              barPercentage: 1.0,
              categoryPercentage: 0.9,
            }}],
            yAxes: [{{
              scaleLabel: {{
                display: false,
                labelString: 'Iterations Count'
              }}
            }}]
           }}
         }}
       }});
     }});
    </script>
  </tr>"""

LATENCY_BASELINE_TEMPLATE = """{val:.2f}ms"""
LATENCY_DIFF_TEMPLATE = """{val:.2f}ms <span class='{span}'>
({diff_val:.2f}ms, {diff:.1f}%)</span>"""


ACCURACY_HEADERS_TOPK_TEMPLATE = """
<th>Top 1</th>
<th>Top 2</th>
<th>Top 3</th>
<th>Top 4</th>
<th>Top 5</th>
"""
ACCURACY_VALUES_TOPK_TEMPLATE = """
<td>{top1}</td>
<td>{top2}</td>
<td>{top3}</td>
<td>{top4}</td>
<td>{top5}</td>
"""
TOPK_BASELINE_TEMPLATE = """{val:.3f}%"""
TOPK_DIFF_TEMPLATE = """{val:.3f}% <span class='{span}'>({diff:.1f}%)</span>"""


ACCURACY_HEADERS_MELCEPLOGF0_TEMPLATE = """
<th>Max log(F0) error</th>
<th>Max Mel Cep distortion</th>
<th>Max scalar error</th>
"""

ACCURACY_VALUES_MELCEPLOGF0_TEMPLATE = """
<td>{max_log_f0}</td>
<td>{max_mel_cep_distortion}</td>
<td>{max_single_error}</td>
"""

MELCEPLOGF0_BASELINE_TEMPLATE = """{val:.2E}"""
MELCEPLOGF0_DIFF_TEMPLATE = """{val:.2E} <span class='{span}'>({diff:.1f}%)</span>"""


ACCURACY_HEADERS_BASIC_TEMPLATE = """
<th>Max single scalar error</th>
"""


ACCURACY_VALUES_BASIC_TEMPLATE = """
<td>{max_single_error:.2f}</td>
"""

CHART_JS_FILE = "Chart.bundle.min.js"

if __name__ == '__main__':
  main()
