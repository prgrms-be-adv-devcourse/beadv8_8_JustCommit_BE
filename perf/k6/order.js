// 주문 · 결제 한계점 (#351): 로그인한 구매자가 장바구니 담기 → 주문(지갑 결제)
// 실행: perf/k6/run.sh order.js -e FIRST_ID=<첫 상품 id> -e LAST_ID=<끝 상품 id> [-e STEPS=5,10,20,40,80 -e HOLD=2m]
//   상품 id 범위는 perf/data/more-products.sql 출력 (새로 넣은 판매 중 상품, 주문마다 하나씩 판매 완료)
// 주문 경로에서 보는 것: 구매자 지갑 · HOLDING 지갑 행 잠금(가설 1), 커밋 뒤 같은 요청 스레드의 택배 예약(가설 4)
import { check, fail } from 'k6';
import exec from 'k6/execution';
import http from 'k6/http';
import { BASE_URL, BUYERS, buyerEmail, post, requirePassword } from './lib/common.js';

const STEPS = (__ENV.STEPS || '5,10,20,40,80').split(',').map(Number);
const HOLD = __ENV.HOLD || '2m';
const RAMP = __ENV.RAMP || '10s';
const FIRST_ID = Number(__ENV.FIRST_ID);
const LAST_ID = Number(__ENV.LAST_ID);
const MAX_VUS = Number(__ENV.MAX_VUS || 400);
// 구매자 한 명은 한 VU만 쓴다 (같은 장바구니를 동시에 건드리지 않게) → 미리 로그인할 구매자 수 = 최대 VU
const LOGIN_BUYERS = Math.min(MAX_VUS, BUYERS);

const stages = [];
for (const rate of STEPS) {
  stages.push({ target: rate, duration: RAMP }, { target: rate, duration: HOLD });
}

export const options = {
  setupTimeout: '5m',
  scenarios: {
    order: {
      executor: 'ramping-arrival-rate',
      startRate: STEPS[0],
      timeUnit: '1s',
      preAllocatedVUs: Math.min(50, MAX_VUS),
      maxVUs: MAX_VUS,
      stages,
    },
  },
  thresholds: {
    http_req_failed: [{ threshold: 'rate<0.30', abortOnFail: true, delayAbortEval: '30s' }],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// 측정 중 로그인(bcrypt)이 섞이면 주문이 아니라 로그인 비용을 재게 되므로 미리 해 둔다 (액세스 토큰 30분)
export function setup() {
  requirePassword();
  if (!FIRST_ID || !LAST_ID) fail('FIRST_ID · LAST_ID 필요 (perf/data/more-products.sql 출력)');
  const tokens = [];
  for (let start = 1; start <= LOGIN_BUYERS; start += 20) {
    const batch = [];
    for (let n = start; n < Math.min(start + 20, LOGIN_BUYERS + 1); n++) {
      batch.push(['POST', `${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({ email: buyerEmail(n), password: __ENV.PERF_PASSWORD }),
        { headers: { 'Content-Type': 'application/json' }, tags: { name: 'setup login' } }]);
    }
    for (const res of http.batch(batch)) {
      if (res.status !== 200) fail(`로그인 실패 ${res.status}`);
      tokens.push(res.json('data.accessToken'));
    }
  }
  return { tokens };
}

export default function (data) {
  const token = data.tokens[(exec.vu.idInTest - 1) % data.tokens.length];
  // 반복마다 다른 상품 (전체 반복 번호 = 상품 순번) — 같은 상품 재고 다툼은 「동시 구매」에서 따로 잰다
  const productId = FIRST_ID + exec.scenario.iterationInTest;
  if (productId > LAST_ID) fail(`상품 부족: ${productId} > ${LAST_ID}`);

  const add = post('/api/v1/cart/items', { productId }, token, 'POST /cart/items');
  if (!check(add, { '장바구니 200': (r) => r.status === 200 })) return;

  const order = post('/api/v1/orders/cart', {
    cartItemIds: [add.json()],
    recipientName: '부하구매자',
    recipientPhone: '01000000000',
    zipcode: '00000',
    address1: '서울시 테스트구 부하로 1',
  }, token, 'POST /orders/cart');
  check(order, {
    '주문 200': (r) => r.status === 200,
    '결제 완료(PAID)': (r) => r.status === 200 && r.json('status') === 'PAID',
  });
}
