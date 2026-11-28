// 상품 키워드 검색 · 품종 의미 검색을 낮은 고정 속도로 보내 단계별 시간(#634)을 모은다
// 한계점이 아니라 「평소 한 요청이 어느 단계에서 시간을 쓰는가」를 보는 측정이라 부하를 낮게 둔다
// 실행 (로그인 필요 없음, 비밀번호 파일 없이 k6 직접):
//   k6 run -e TARGET=product -e RPM=120 -e DURATION=5m --out experimental-prometheus-rw search-stages.js
//   k6 run -e TARGET=species -e RPM=9 -e DURATION=6m --out experimental-prometheus-rw search-stages.js
// 품종 의미 검색은 Gateway가 IP당 1분 10회로 제한한다 → 공개 주소로는 RPM 9 이하 (넘으면 429가 섞임)
// 단계별 표: python3 -I perf/analysis/stage_timing.py --start <시작> --end <끝> (perf/search-chatbot.md 「검색 · 챗봇 단계별 운영 측정」)
import { check } from 'k6';
import { get, pick } from './lib/common.js';
import { PRODUCT_KEYWORDS, SPECIES_QUERIES } from './lib/search-stages-data.js';

const TARGET = __ENV.TARGET || 'product';
const RPM = Number(__ENV.RPM || (TARGET === 'species' ? 9 : 120));
if (!['product', 'species'].includes(TARGET)) throw new Error(`TARGET은 product 또는 species: ${TARGET}`);

export const options = {
  scenarios: {
    [TARGET]: {
      executor: 'constant-arrival-rate',
      rate: RPM,
      timeUnit: '1m',
      duration: __ENV.DURATION || '5m',
      preAllocatedVUs: 5,
      maxVUs: 20,
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  if (TARGET === 'product') {
    const res = get(`/api/v1/products?keyword=${encodeURIComponent(pick(PRODUCT_KEYWORDS))}&size=20`, null,
      'GET /products?keyword');
    check(res, { '200': (r) => r.status === 200 });
  } else {
    const res = get(`/api/v1/speciesSearches?query=${encodeURIComponent(pick(SPECIES_QUERIES))}&size=5`, null,
      'GET /speciesSearches');
    check(res, { '200': (r) => r.status === 200, '429 아님': (r) => r.status !== 429 });
  }
}
