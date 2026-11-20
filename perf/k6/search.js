// 키워드 검색만 고정 속도로 (#571): browse.js의 검색 요청(품종 이름으로 검색, size=20)만 떼어 낸 것
// browse.js는 목록 · 상세 · 품종이 섞여 결과에서 검색만의 p95를 볼 수 없어서 따로 만들었다
// 실행: k6 run -e BASE_URL=http://localhost:18082 -e RPS=40 -e DURATION=1m --summary-export <결과.json> search.js
//   단계를 올리며 재기: perf/k6/search-steps.sh (단계마다 따로 실행 → 단계마다 요약 파일 하나)
import { check } from 'k6';
import { get, pick } from './lib/common.js';

const RPS = Number(__ENV.RPS || 10);

export const options = {
  scenarios: {
    search: {
      executor: 'constant-arrival-rate', // 응답이 느려져도 초당 RPS개를 계속 보낸다 (못 보내면 dropped_iterations)
      rate: RPS,
      timeUnit: '1s',
      duration: __ENV.DURATION || '1m',
      preAllocatedVUs: Math.min(RPS * 2, 400),
      maxVUs: Number(__ENV.MAX_VUS || 800),
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// 검색어 = 품종 이름 (browse.js와 같음, 측정 시간에 들어가지 않음)
export function setup() {
  const species = get('/api/v1/species?size=500', null, 'setup').json('data') || [];
  const names = (Array.isArray(species) ? species : species.content || []).map((s) => s.name).filter(Boolean);
  if (names.length === 0) throw new Error('품종 없음: product 앱과 테스트 데이터를 먼저 준비할 것');
  return { names };
}

export default function (data) {
  const res = get(`/api/v1/products?keyword=${encodeURIComponent(pick(data.names))}&size=20`, null, 'GET /products?keyword');
  check(res, { '200': (x) => x.status === 200 });
}
