// 동시 구매 (#351): 같은 상품 하나를 구매자 50명이 동시에 주문 — 딱 1명만 결제되고 나머지는 거절되는지, 얼마나 걸리는지
// 실행: perf/k6/run.sh concurrent.js -e FIRST_ID=<첫 상품 id> [-e ROUNDS=10 -e BUYERS_PER_ROUND=50]
//   라운드마다 다른 상품 1개 (FIRST_ID + 라운드 번호). 장바구니 담기는 setup에서 미리 → 주문 요청만 동시에 몰린다
//   끝난 뒤 DB 확인: perf/data/check-concurrent.sql (상품마다 결제된 주문 1건 · 지갑 금액이 맞는지)
import { check, fail } from 'k6';
import exec from 'k6/execution';
import http from 'k6/http';
import { BASE_URL, buyerEmail, post, requirePassword } from './lib/common.js';

const FIRST_ID = Number(__ENV.FIRST_ID);
const ROUNDS = Number(__ENV.ROUNDS || 10);
const PER_ROUND = Number(__ENV.BUYERS_PER_ROUND || 50);
const GAP = Number(__ENV.GAP_SEC || 10); // 라운드 간격 (앞 라운드가 끝나고 시작)
// 라운드마다 다른 구매자 묶음 (같은 구매자가 여러 라운드에 겹치지 않게): 라운드 r은 구매자 r*50+1 ~ r*50+50
const BUYER_OFFSET = Number(__ENV.BUYER_OFFSET || 1000); // 주문 측정(1~400)에 쓴 구매자와 겹치지 않게

const scenarios = {};
for (let r = 0; r < ROUNDS; r++) {
  scenarios[`round${r}`] = {
    executor: 'per-vu-iterations',
    exec: 'buy',
    vus: PER_ROUND,
    iterations: 1,
    startTime: `${r * GAP}s`,
    maxDuration: `${GAP + 30}s`,
    env: { ROUND: String(r) },
    tags: { round: String(r) },
  };
}

export const options = {
  setupTimeout: '10m',
  scenarios,
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

// 라운드 r의 구매자들이 상품 FIRST_ID + r을 장바구니에 미리 담는다
export function setup() {
  requirePassword();
  if (!FIRST_ID) fail('FIRST_ID 필요 (판매 중이고 아무도 담지 않은 상품 id)');
  const rounds = [];
  for (let r = 0; r < ROUNDS; r++) {
    const productId = FIRST_ID + r;
    const emails = [];
    for (let i = 1; i <= PER_ROUND; i++) emails.push(buyerEmail(BUYER_OFFSET + r * PER_ROUND + i));
    const tokens = loginAll(emails);
    const carts = tokens.map((token) => {
      const add = post('/api/v1/cart/items', { productId }, token, 'setup cart');
      if (add.status !== 200) fail(`장바구니 실패 ${add.status} ${add.body}`);
      return add.json();
    });
    rounds.push({ productId, tokens, carts });
  }
  return { rounds };
}

export function buy(data) {
  const round = data.rounds[Number(__ENV.ROUND)];
  const i = exec.scenario.iterationInTest; // 이 라운드 안에서 0 ~ 49
  const res = post('/api/v1/orders/cart', {
    cartItemIds: [round.carts[i]],
    recipientName: '동시구매자',
    recipientPhone: '01000000000',
    zipcode: '00000',
    address1: '서울시 테스트구 동시로 1',
  }, round.tokens[i], 'POST /orders/cart (동시)');
  const paid = res.status === 200 && res.json('status') === 'PAID';
  check(res, {
    '결제됨 또는 품절 거절(4xx)': (r) => paid || (r.status >= 400 && r.status < 500),
    '서버 오류 아님': (r) => r.status < 500,
  });
  if (paid) console.log(`round=${__ENV.ROUND} product=${round.productId} PAID by #${i} orderId=${res.json('orderId')}`);
}
