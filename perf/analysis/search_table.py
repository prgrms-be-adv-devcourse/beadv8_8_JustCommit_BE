"""검색 단계별 표 (#571): search-steps.sh가 남긴 k6 요약 파일들을 한 표로

사용: python3 -I perf/analysis/search_table.py perf/k6/results/search-<LABEL>-*.json
열: 보낸 속도(목표) · 실제 처리량 · p50 · p95 · p99 · 오류율 · 못 보낸 요청(dropped)
"""
import json
import re
import sys


def row(path):
    m = json.load(open(path))['metrics']
    d = m['http_req_duration']
    failed = m['http_req_failed']
    total = failed.get('passes', 0) + failed.get('fails', 0)
    rps = re.search(r'-(\d+)rps-', path)
    return {
        'file': path.rsplit('/', 1)[-1],
        '목표 rps': int(rps.group(1)) if rps else None,
        '처리 rps': m['http_reqs']['rate'],
        'p50 ms': d['med'],
        'p95 ms': d['p(95)'],
        'p99 ms': d['p(99)'],
        '오류율 %': 100 * failed.get('passes', 0) / total if total else 0,  # http_req_failed는 실패를 passes로 셈
        'dropped': m.get('dropped_iterations', {}).get('count', 0),
    }


def main():
    rows = sorted((row(p) for p in sys.argv[1:]), key=lambda r: (r['file'].rsplit('-', 3)[0], r['목표 rps'] or 0))
    cols = ['목표 rps', '처리 rps', 'p50 ms', 'p95 ms', 'p99 ms', '오류율 %', 'dropped']
    print('| 파일 | ' + ' | '.join(cols) + ' |')
    print('|---' * (len(cols) + 1) + '|')
    for r in rows:
        print(f"| {r['file']} | " + ' | '.join(
            f'{r[c]:.1f}' if isinstance(r[c], float) else str(r[c]) for c in cols) + ' |')


if __name__ == '__main__':
    main()
