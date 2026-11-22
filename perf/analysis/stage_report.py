"""부하 단계별 결과표 (#351)

k6가 초당 요청 수를 계단처럼 올린 측정에서, 단계마다 Prometheus 값을 뽑아 표로 만든다.
단계가 시작하고 RAMP + WARMUP 초가 지난 뒤부터 단계 끝까지만 본다 (올라가는 중간 값은 빼기 위함).

사용:
  python -I perf/analysis/stage_report.py --start 2026-11-11T13:00:00+09:00 --steps 10,20,40 --hold 120
  (--start는 k6 시작 시각. run.sh가 출력하는 「시작:」 줄 값을 쓴다)

판정 기준(노션 「판정 기준」): p95 2초 초과 또는 에러율 5% 초과가 처음 나온 단계 = 한계점
"""
import argparse
import json
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timedelta

QUERIES = {
    # 이름: (PromQL 템플릿, 형식). {w}는 구간 길이, 값은 구간 끝 시각 기준
    'k6 보냄 rps': ('sum(increase(k6_http_reqs_total{{name!="setup"}}[{w}])) / {s}', '{:.1f}'),
    '서버 처리 rps': ('sum(increase(http_server_requests_seconds_count{{job="app"}}[{w}])) / {s}', '{:.1f}'),
    '서버 p95 ms': ('1000 * histogram_quantile(0.95, sum by (le) (increase(http_server_requests_seconds_bucket{{job="app"}}[{w}])))', '{:.0f}'),
    '서버 p99 ms': ('1000 * histogram_quantile(0.99, sum by (le) (increase(http_server_requests_seconds_bucket{{job="app"}}[{w}])))', '{:.0f}'),
    'k6 p95 ms(근사)': ('1000 * avg_over_time(max(k6_http_req_duration_p95{{name!="setup"}})[{w}:5s])', '{:.0f}'),
    '에러율 %': ('100 * sum(increase(k6_http_reqs_total{{name!="setup",expected_response="false"}}[{w}])) / sum(increase(k6_http_reqs_total{{name!="setup"}}[{w}]))', '{:.2f}'),
    '못 보냄(dropped)': ('sum(increase(k6_dropped_iterations_total[{w}]))', '{:.0f}'),
    '서버 CPU 평균 %': ('100 * avg_over_time((1 - avg(rate(node_cpu_seconds_total{{job="node",mode="idle"}}[30s])))[{w}:5s])', '{:.0f}'),
    '서버 CPU 최대 %': ('100 * max_over_time((1 - avg(rate(node_cpu_seconds_total{{job="node",mode="idle"}}[30s])))[{w}:5s])', '{:.0f}'),
    'steal 최대 %': ('100 * max_over_time(avg(rate(node_cpu_seconds_total{{job="node",mode="steal"}}[2m]))[{w}:5s])', '{:.1f}'),
    'Hikari 사용 최대': ('max_over_time(hikaricp_connections_active{{job="app"}}[{w}])', '{:.0f}'),
    'Hikari 대기 최대': ('max_over_time(hikaricp_connections_pending{{job="app"}}[{w}])', '{:.0f}'),
    'Tomcat 바쁨 최대': ('max_over_time(tomcat_threads_busy_threads{{job="app"}}[{w}])', '{:.0f}'),
    '힙 최대 MB': ('max_over_time(sum(jvm_memory_used_bytes{{job="app",area="heap"}})[{w}:5s]) / 1048576', '{:.0f}'),
    'DB 잠금 최대': ('max_over_time(sum(pg_locks_count{{job="postgres"}})[{w}:5s])', '{:.0f}'),
}


# MSA 운영(#628): 운영 Prometheus(데이터 서버, SSH 터널 localhost:19090)의 라벨 job="spring" · service=…, node는 server=app|data
# 「서버 처리 · p95」는 사용자가 보는 값인 Gateway 기준(세미의 앱 하나와 같은 자리), 서비스별 값은 따로 뽑는다
_SVC = 'job="spring",uri!~"/actuator.*"'
_CPU = '100 * {agg}_over_time((1 - avg(rate(node_cpu_seconds_total{{{{job="node",server="{srv}",mode="idle"}}}}[2m])))[{{w}}:5s])'
QUERIES_MSA = {
    'k6 보냄 rps': QUERIES['k6 보냄 rps'],
    '서버 처리 rps(GW)': ('sum(increase(http_server_requests_seconds_count{{' + _SVC + ',service="gateway"}}[{w}])) / {s}', '{:.1f}'),
    '서버 p95 ms(GW)': ('1000 * histogram_quantile(0.95, sum by (le) (increase(http_server_requests_seconds_bucket{{' + _SVC + ',service="gateway"}}[{w}])))', '{:.0f}'),
    '서버 p99 ms(GW)': ('1000 * histogram_quantile(0.99, sum by (le) (increase(http_server_requests_seconds_bucket{{' + _SVC + ',service="gateway"}}[{w}])))', '{:.0f}'),
    'k6 p95 ms(근사)': QUERIES['k6 p95 ms(근사)'],
    '에러율 %': QUERIES['에러율 %'],
    '못 보냄(dropped)': QUERIES['못 보냄(dropped)'],
    **{f'{svc} p95 ms': ('1000 * histogram_quantile(0.95, sum by (le) (increase(http_server_requests_seconds_bucket{{' + _SVC + f',service="{svc}"}}}}[{{w}}])))', '{:.0f}')
       for svc in ('product', 'market', 'payment')},
    '앱 서버 CPU 평균 %': (_CPU.format(agg='avg', srv='app'), '{:.0f}'),
    '앱 서버 CPU 최대 %': (_CPU.format(agg='max', srv='app'), '{:.0f}'),
    '데이터 서버 CPU 최대 %': (_CPU.format(agg='max', srv='data'), '{:.0f}'),
    'steal 최대 %': ('100 * max_over_time(max(rate(node_cpu_seconds_total{{job="node",mode="steal"}}[2m]))[{w}:5s])', '{:.1f}'),
    **{f'Hikari {svc} 사용/대기 최대': (f'max_over_time(max(hikaricp_connections_active{{{{job="spring",service="{svc}"}}}})[{{w}}:5s]) * 1000 + max_over_time(max(hikaricp_connections_pending{{{{job="spring",service="{svc}"}}}})[{{w}}:5s])', 'hikari')
       for svc in ('product', 'market', 'payment')},
    'Tomcat 바쁨 최대(market)': ('max_over_time(max(tomcat_threads_busy_threads{{job="spring",service="market"}})[{w}:5s])', '{:.0f}'),
    '힙 최대 MB(product)': ('max_over_time(sum(jvm_memory_used_bytes{{job="spring",service="product",area="heap"}})[{w}:5s]) / 1048576', '{:.0f}'),
    'DB 잠금 최대': QUERIES['DB 잠금 최대'],
}


def query(base, promql, at):
    url = f"{base}/api/v1/query?" + urllib.parse.urlencode({'query': promql, 'time': at.timestamp()})
    with urllib.request.urlopen(url, timeout=30) as r:
        data = json.load(r)
    result = data.get('data', {}).get('result', [])
    if not result:
        return None
    value = float(result[0]['value'][1])
    return None if value != value else value  # NaN → None


def fmt(f, v):
    if f is None:
        return str(v)
    if f == 'hikari':  # 사용 * 1000 + 대기를 한 쿼리로 받아 「사용/대기」로 보여 준다
        return f'{int(v // 1000)}/{int(v % 1000)}'
    return f.format(v)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--start', required=True, help='k6 시작 시각 (ISO 8601, 시간대 포함)')
    p.add_argument('--steps', required=True, help='단계 rps 목록, 예: 10,20,40')
    p.add_argument('--hold', type=int, default=120, help='단계 유지 초')
    p.add_argument('--ramp', type=int, default=10, help='단계 사이 올라가는 초')
    p.add_argument('--warmup', type=int, default=20, help='단계 시작 후 버리는 초')
    p.add_argument('--prometheus', default='http://localhost:19090')
    p.add_argument('--json', help='결과를 JSON으로도 저장할 경로')
    p.add_argument('--target', choices=['semi', 'msa'], default='semi', help='msa = 운영 MSA 라벨(#628)')
    a = p.parse_args()

    queries = QUERIES_MSA if a.target == 'msa' else QUERIES
    start = datetime.fromisoformat(a.start)
    steps = [int(x) for x in a.steps.split(',')]
    rows = []
    for i, rps in enumerate(steps):
        stage_start = start + timedelta(seconds=i * (a.ramp + a.hold))
        begin = stage_start + timedelta(seconds=a.ramp + a.warmup)
        end = stage_start + timedelta(seconds=a.ramp + a.hold)
        if begin >= datetime.now(begin.tzinfo):
            break
        end = min(end, datetime.now(end.tzinfo))
        secs = int((end - begin).total_seconds())
        if secs < 15:
            break
        row = {'목표 rps': rps, '구간': f"{begin:%H:%M:%S}~{end:%H:%M:%S}"}
        for name, (tpl, _) in queries.items():
            row[name] = query(a.prometheus, tpl.format(w=f'{secs}s', s=secs), end)
        rows.append(row)

    cols = ['목표 rps', '구간'] + list(queries)
    fmts = {name: f for name, (_, f) in queries.items()}
    print('| ' + ' | '.join(cols) + ' |')
    print('|' + '---|' * len(cols))
    for row in rows:
        cells = []
        for c in cols:
            v = row[c]
            cells.append('-' if v is None else fmt(fmts.get(c), v))
        print('| ' + ' | '.join(cells) + ' |')
    if a.json:
        with open(a.json, 'w', encoding='utf-8') as f:
            json.dump(rows, f, ensure_ascii=False, indent=2)


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main()
