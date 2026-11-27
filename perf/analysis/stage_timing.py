"""검색 · 챗봇 단계별 p50 · p95 표 (#634)

측정 구간 [--start, --end] 동안 운영 Prometheus에 쌓인 단계별 Timer 히스토그램에서 단계마다 성공 건수 · p50 · p95와
실패 건수 · 실패 p95(태그 outcome=failure)를 뽑아 Markdown 표로 출력한다.
전체(total)를 뺀 단계 중 성공 p95가 가장 큰 단계에 ← 가장 느림 을 붙인다.

지표 (서비스가 내보냄, docs/infra/observability.md):
  product_search_stage_seconds   상품 키워드 검색  total · opensearch · opensearch_took · db · fallback
  ai_species_search_stage_seconds 품종 의미 검색   total · embedding · vector_search · product_lookup
  ai_chatbot_stage_seconds        챗봇 메시지      total · history · embedding · product_search · species_search · answer · save
같은 구간의 HTTP 응답 시간(서비스 http.server.requests, Gateway)도 함께 뽑아 단계 합과 비교한다.

사용 (운영 Grafana를 거쳐 Prometheus 조회, 토큰은 환경변수 GRAFANA_SERVICE_ACCOUNT_TOKEN → docs/infra/observability.md):
  python3 -I perf/analysis/stage_timing.py --grafana https://replant.kro.kr/grafana \
      --start 2026-11-26T14:00:00+09:00 --end 2026-11-26T14:05:00+09:00 --only product
  SSH 터널(localhost:19090 → 운영 Prometheus)이 있으면 --grafana 없이 --prometheus로 바로 조회한다
  --only 없이 주면 세 가지 모두. 구간은 k6 시작 · 끝 시각 (Prometheus 수집이 60초라 앞뒤로 1분씩 넉넉히 줘도 된다)

주의: 히스토그램 구간 경계 사이 값은 선형 보간이라 ms 단위 숫자는 근사다. 건수가 20개보다 적으면 p95는 참고만.
"""
import argparse
import json
import os
import sys
import urllib.parse
import urllib.request
from datetime import datetime

TARGETS = {
    'product': {
        'title': '상품 키워드 검색 GET /api/v1/products?keyword=',
        'metric': 'product_search_stage_seconds',
        'service': 'product',
        'stages': ['total', 'opensearch', 'opensearch_took', 'db', 'fallback'],
        'uri': '/api/v1/products',
    },
    'species': {
        'title': '품종 의미 검색 GET /api/v1/speciesSearches',
        'metric': 'ai_species_search_stage_seconds',
        'service': 'ai',
        'stages': ['total', 'embedding', 'vector_search', 'product_lookup'],
        'uri': '/api/v1/speciesSearches',
    },
    'chatbot': {
        'title': '챗봇 POST /api/v1/chatbot/messages',
        'metric': 'ai_chatbot_stage_seconds',
        'service': 'ai',
        'stages': ['total', 'history', 'embedding', 'product_search', 'species_search', 'answer', 'save'],
        'uri': '/api/v1/chatbot/messages',
    },
}
# opensearch_took은 opensearch 안에 들어 있는 값이라 「가장 느린 단계」 비교에서 뺀다
NOT_A_STEP = {'total', 'opensearch_took'}


HEADERS = {}


def query(base, promql, at):
    url = f'{base}/api/v1/query?' + urllib.parse.urlencode({'query': promql, 'time': at})
    with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=30) as r:
        body = json.load(r)
    if body.get('status') != 'success':
        raise RuntimeError(f'Prometheus 오류: {body}')
    return body['data']['result']


def by_stage(result):
    values = {}
    for row in result:
        v = float(row['value'][1])
        values[row['metric'].get('stage', '')] = None if v != v else v  # NaN → None
    return values


def single(result):
    if not result:
        return None
    v = float(result[0]['value'][1])
    return None if v != v else v


def ms(v):
    return '-' if v is None else f'{v * 1000:,.0f}'


def report(base, name, t, end, window):
    def sel(outcome):
        return f'job="spring",service="{t["service"]}",outcome="{outcome}"'

    def bucket(outcome):
        return f'sum by (le, stage) (increase({t["metric"]}_bucket{{{sel(outcome)}}}[{window}]))'

    def count(outcome):
        return by_stage(query(base, f'sum by (stage) (increase({t["metric"]}_count{{{sel(outcome)}}}[{window}]))', end))

    counts = count('success')
    p50 = by_stage(query(base, f'histogram_quantile(0.5, {bucket("success")})', end))
    p95 = by_stage(query(base, f'histogram_quantile(0.95, {bucket("success")})', end))
    fail_counts = count('failure')
    fail_p95 = by_stage(query(base, f'histogram_quantile(0.95, {bucket("failure")})', end))

    http_sel = f'job="spring",service="{t["service"]}",uri="{t["uri"]}"'
    http_bucket = f'sum by (le) (increase(http_server_requests_seconds_bucket{{{http_sel}}}[{window}]))'
    http_p50 = single(query(base, f'histogram_quantile(0.5, {http_bucket})', end))
    http_p95 = single(query(base, f'histogram_quantile(0.95, {http_bucket})', end))

    steps = {s: p95.get(s) for s in t['stages'] if s not in NOT_A_STEP and (counts.get(s) or 0) >= 1}
    slowest = max(steps, key=lambda s: steps[s] or 0) if steps else None

    print(f'\n### {t["title"]}\n')
    print('| 단계 | 건수 | p50 ms | p95 ms | 실패 건수 | 실패 p95 ms | |')
    print('|---|---|---|---|---|---|---|')
    for s in t['stages']:
        mark = '← 가장 느림' if s == slowest else ''
        fails = fail_counts.get(s) or 0
        print(f'| `{s}` | {counts.get(s) or 0:.0f} | {ms(p50.get(s))} | {ms(p95.get(s))} | {fails:.0f} | '
              f'{ms(fail_p95.get(s)) if fails else "-"} | {mark} |')
    print(f'| (HTTP {t["service"]}) | | {ms(http_p50)} | {ms(http_p95)} | | | 서비스가 본 응답 시간 (성공 · 실패 모두) |')
    return {'counts': counts, 'p50': p50, 'p95': p95, 'fail_counts': fail_counts, 'fail_p95': fail_p95,
            'http_p50': http_p50, 'http_p95': http_p95, 'slowest': slowest}


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--start', required=True, help='측정 시작 (ISO 8601, 시간대 포함)')
    p.add_argument('--end', required=True, help='측정 끝 (ISO 8601, 시간대 포함)')
    p.add_argument('--only', choices=sorted(TARGETS), action='append', help='이것만 (여러 번 줄 수 있음)')
    p.add_argument('--prometheus', default='http://localhost:19090')
    p.add_argument('--grafana', help='Grafana 주소 (예: https://replant.kro.kr/grafana). 주면 데이터 소스 프록시로 조회')
    p.add_argument('--json', help='결과를 JSON으로도 저장할 경로')
    a = p.parse_args()
    if a.grafana:
        token = os.environ.get('GRAFANA_SERVICE_ACCOUNT_TOKEN')
        if not token:
            sys.exit('환경변수 GRAFANA_SERVICE_ACCOUNT_TOKEN이 없음 (docs/infra/observability.md 「Claude Code로 로그 조회 · 요약」)')
        HEADERS['Authorization'] = f'Bearer {token}'
        a.prometheus = a.grafana.rstrip('/') + '/api/datasources/proxy/uid/prometheus'

    start, end = datetime.fromisoformat(a.start), datetime.fromisoformat(a.end)
    seconds = int((end - start).total_seconds())
    if seconds < 60:
        sys.exit('구간이 60초보다 짧음: 운영 수집 주기가 60초라 값이 비어 나온다')
    window = f'{seconds}s'
    print(f'구간: {a.start} ~ {a.end} ({seconds}초)')

    results = {}
    for name in a.only or list(TARGETS):
        results[name] = report(a.prometheus, name, TARGETS[name], end.timestamp(), window)
    if a.json:
        with open(a.json, 'w', encoding='utf-8') as f:
            json.dump({'start': a.start, 'end': a.end, 'results': results}, f, ensure_ascii=False, indent=2)


if __name__ == '__main__':
    main()
