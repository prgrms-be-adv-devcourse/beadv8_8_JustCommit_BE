// 내 주문 · 판매 목록 (#351, 가설 2 인덱스): 로그인한 구매자 · 판매자가 목록 첫 페이지를 본다
// 실행: perf/k6/run.sh mypage.js [-e STEPS=10,20,40 -e HOLD=1m]
//   같은 스크립트를 「지금 주문 수」 · 「지난 주문 10만 건 추가(perf/data/bulk-orders.sql)」 · 「인덱스 추가」 순서로 돌려 비교한다
// 비율 (가정): 구매자 내 주문 50% · 판매자 판매 주문 30% · 판매자 정산 20%
import { check, fail } from 'k6';
import http from 'k6/http';
import { BASE_URL, BUYERS, SELLERS, buyerEmail, get, pick, requirePassword, sellerEmail } from './lib/common.js';

const STEPS = (__ENV.STEPS || '10,20,40').split(',').map(Number);
const HOLD = __ENV.HOLD || '1m';
const LOGINS = Number(__ENV.LOGINS || 100); // 구매자 · 판매자 각각 미리 로그인할 수

const stages = [];
for (const rate of STEPS) stages.push({ target: rate, duration: '10s' }, { target: rate, duration: HOLD });

export const options = {
  setupTimeout: '5m',
  scenarios: {
    mypage: {
      executor: 'ramping-arrival-rate',
      startRate: STEPS[0],
      timeUnit: '1s',
      preAllocatedVUs: 30,
      maxVUs: 300,
      stages,
    },
  },
  thresholds: {
    http_req_failed: [{ threshold: 'rate<0.30', abortOnFail: true, delayAbortEval: '30s' }],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function loginAll(emails) {
  const tokens = [];
  for (let i = 0; i < emails.length; i += 25) {
    const batch = emails.slice(i, i + 25).map((email) => ['POST', `${BASE_URL}/api/v1/auth/login`,
      JSON.stringify({ email, password: __ENV.PERF_PASSWORD }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'setup login' } }]);
    for (const res of http.batch(batch)) {
      if (res.status !== 200) fail(`로그인 실패 ${res.status}`);
      tokens.push(res.json('data.accessToken'));
    }
  }
  return tokens;
}

export function setup() {
  requirePassword();
  // 구매자는 지난 주문이 고르게 퍼져 있으니 1~LOGINS, 판매자도 1~LOGINS (판매자 200명 중)
  const buyers = loginAll(Array.from({ length: Math.min(LOGINS, BUYERS) }, (_, i) => buyerEmail(i + 1)));
  const sellers = loginAll(Array.from({ length: Math.min(LOGINS, SELLERS) }, (_, i) => sellerEmail(i + 1)));
  return { buyers, sellers };
}

export default function (data) {
  const r = Math.random();
  let res;
  if (r < 0.5) {
    res = get('/api/v1/orders?page=0&size=10', pick(data.buyers), 'GET /orders');
  } else if (r < 0.8) {
    res = get('/api/v1/orders/seller?page=0&size=10', pick(data.sellers), 'GET /orders/seller');
  } else {
    res = get('/api/v1/settlements?page=0&size=10', pick(data.sellers), 'GET /settlements');
  }
  check(res, { '200': (x) => x.status === 200 });
}
