// 둘러보기 한계점 (#351): 로그인 없이 상품 목록 · 검색 · 상세 · 품종을 섞어서, 초당 요청 수를 계단처럼 올린다
// 실행: perf/k6/run.sh browse.js
//   단계 바꾸기: -e STEPS=10,20,40,80,160,320 -e HOLD=2m
// 확인할 가설: 3 (커넥션 풀 10개 · 캐시 없음 · LIKE 검색 + count + OFFSET)
import { check } from 'k6';
import { get, pick, randomInt } from './lib/common.js';

const STEPS = (__ENV.STEPS || '10,20,40,80,160,320').split(',').map(Number);
const HOLD = __ENV.HOLD || '2m';
const RAMP = '10s'; // 다음 단계로 올라가는 시간

// 요청 1번 = 반복 1번 → 반복 속도가 곧 초당 요청 수(RPS)
const stages = [];
for (const rps of STEPS) {
  stages.push({ target: rps, duration: RAMP });
  stages.push({ target: rps, duration: HOLD });
}

export const options = {
  scenarios: {
    browse: {
      executor: 'ramping-arrival-rate',
      startRate: STEPS[0],
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: Number(__ENV.MAX_VUS || 800), // 응답이 느려지면 동시에 기다리는 사용자가 늘어난다 (부족하면 dropped_iterations)
      stages,
    },
  },
  thresholds: {
    // 안전장치: 누적 기준이라 한계점 판정용이 아님 (판정은 단계별 결과로)
    http_req_failed: [{ threshold: 'rate<0.30', abortOnFail: true, delayAbortEval: '1m' }],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// 상세 · 검색에 쓸 상품 id와 품종 이름을 미리 모은다 (측정 시간에 들어가지 않음)
export function setup() {
  const ids = [];
  for (let i = 0; i < 10; i++) {
    const res = get(`/api/v1/products?onSale=true&page=${randomInt(0, 190)}&size=50`, null, 'setup');
    (res.json('data.content') || []).forEach((p) => ids.push(p.productId));
  }
  const species = get('/api/v1/species', null, 'setup').json('data') || [];
  const names = (Array.isArray(species) ? species : species.content || []).map((s) => s.name).filter(Boolean);
  if (ids.length === 0 || names.length === 0) throw new Error('준비 데이터 없음: 테스트 데이터를 먼저 넣을 것');
  return { ids, names };
}

// 화면 비율 (가정): 목록 40% · 상세 30% · 검색 20% · 품종 10%
export default function (data) {
  const r = Math.random();
  let res;
  if (r < 0.4) {
    res = get(`/api/v1/products?page=${randomInt(0, 20)}&size=20`, null, 'GET /products');
  } else if (r < 0.7) {
    res = get(`/api/v1/products/${pick(data.ids)}`, null, 'GET /products/{id}');
  } else if (r < 0.9) {
    res = get(`/api/v1/products?keyword=${encodeURIComponent(pick(data.names))}&size=20`, null, 'GET /products?keyword');
  } else {
    res = get('/api/v1/species', null, 'GET /species');
  }
  check(res, { '200': (x) => x.status === 200 });
}
