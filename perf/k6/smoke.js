// Smoke (#351): 1명이 전체 흐름을 한 바퀴 돈다 — 스크립트 · 서버 · 데이터가 맞는지 확인용 (부하 아님)
// 실행: perf/k6/run.sh smoke.js
import { check, group, sleep } from 'k6';
import { buyerEmail, get, login, pick, post, randomInt, requirePassword, sellerEmail, BUYERS, SELLERS } from './lib/common.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate==1.0'], // 하나라도 실패하면 Smoke 실패
  },
};

export function setup() {
  requirePassword();
}

export default function () {
  const token = login(buyerEmail(randomInt(1, BUYERS)));
  if (!token) return;

  let productId;
  group('둘러보기', () => {
    const list = get(`/api/v1/products?onSale=true&page=${randomInt(0, 50)}&size=20`, null, 'GET /products');
    check(list, { '상품 목록 200': (r) => r.status === 200 });
    const items = list.json('data.content') || [];
    check(items, { '상품 목록에 판매 중 상품이 있음': (a) => a.length > 0 });
    productId = items.length ? pick(items).productId : null;

    const species = get('/api/v1/species', null, 'GET /species');
    check(species, { '품종 200': (r) => r.status === 200 });

    const search = get(`/api/v1/products?keyword=${encodeURIComponent('개운죽')}&size=20`, null, 'GET /products?keyword');
    check(search, { '검색 200': (r) => r.status === 200 });

    const detail = get(`/api/v1/products/${productId}`, null, 'GET /products/{id}');
    check(detail, { '상세 200': (r) => r.status === 200 });
  });
  sleep(1);

  group('장바구니 → 주문', () => {
    const add = post('/api/v1/cart/items', { productId }, token, 'POST /cart/items');
    check(add, { '장바구니 담기 200': (r) => r.status === 200 });

    const cart = get('/api/v1/cart', token, 'GET /cart');
    check(cart, { '장바구니 조회 200': (r) => r.status === 200 });
    // 장바구니 API는 ApiResponse로 감싸지 않고 바로 돌려준다 (세미 코드 그대로)
    const cartItem = (cart.json('items') || []).find((i) => i.productId === productId);
    check(cartItem, { '담은 상품이 장바구니에 있음': (i) => !!i });
    if (!cartItem) return;

    const order = post('/api/v1/orders/cart', {
      cartItemIds: [cartItem.cartItemId],
      recipientName: '부하구매자',
      recipientPhone: '01000000000',
      zipcode: '00000',
      address1: '서울시 테스트구 부하로 1',
    }, token, 'POST /orders/cart');
    check(order, { '주문 200': (r) => r.status === 200 });

    const orders = get('/api/v1/orders?page=0&size=10', token, 'GET /orders');
    check(orders, { '주문 목록 200': (r) => r.status === 200 });
  });

  group('판매자 정산 조회', () => {
    const sellerToken = login(sellerEmail(randomInt(1, SELLERS)));
    const settlements = get('/api/v1/settlements?page=0&size=10', sellerToken, 'GET /settlements');
    check(settlements, { '정산 목록 200': (r) => r.status === 200 });
  });
}
