// 섞어서 (#351): 둘러보기 90% + 주문 10%를 동시에, 전체 사용자 행동 수를 계단처럼 올린다
// 실행: perf/k6/run.sh mixed.js -e FIRST_ID=<첫 상품 id> -e LAST_ID=<끝 상품 id> [-e STEPS=10,20,40,80,160 -e HOLD=2m]
//   STEPS = 초당 사용자 행동 수(둘러보기 요청 1개 · 주문 1건 = 장바구니 + 주문 요청 2개)
//   배송 진행(404-express AUTO_PROGRESS)을 켜고 잰다 — 택배 웹훅 · 카프카 이벤트까지 같이 도는 실제 모양
import browse, { setup as browseSetup } from './browse.js';
import order, { setup as orderSetup } from './order.js';

const STEPS = (__ENV.STEPS || '10,20,40,80,160').split(',').map(Number);
const HOLD = __ENV.HOLD || '2m';
const RAMP = '10s';
const ORDER_SHARE = Number(__ENV.ORDER_SHARE || 0.1);

function stagesFor(share) {
  const stages = [];
  for (const rate of STEPS) {
    const target = Math.max(1, Math.round(rate * share));
    stages.push({ target, duration: RAMP }, { target, duration: HOLD });
  }
  return stages;
}

export const options = {
  setupTimeout: '5m',
  scenarios: {
    browse: {
      executor: 'ramping-arrival-rate',
      exec: 'browseOnce',
      startRate: Math.max(1, Math.round(STEPS[0] * (1 - ORDER_SHARE))),
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 600,
      stages: stagesFor(1 - ORDER_SHARE),
    },
    order: {
      executor: 'ramping-arrival-rate',
      exec: 'orderOnce',
      startRate: Math.max(1, Math.round(STEPS[0] * ORDER_SHARE)),
      timeUnit: '1s',
      preAllocatedVUs: 20,
      maxVUs: Number(__ENV.MAX_VUS || 200), // order.js가 이 수만큼 구매자를 미리 로그인한다
      stages: stagesFor(ORDER_SHARE),
    },
  },
  thresholds: {
    http_req_failed: [{ threshold: 'rate<0.30', abortOnFail: true, delayAbortEval: '1m' }],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { browse: browseSetup(), order: orderSetup() };
}

export function browseOnce(data) {
  browse(data.browse);
}

export function orderOnce(data) {
  order(data.order);
}
