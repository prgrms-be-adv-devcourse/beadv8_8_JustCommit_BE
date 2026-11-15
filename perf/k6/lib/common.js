// 부하 측정 공통 (#351) — 로그인 · 요청 헤더 · 무작위 고르기
import http from 'k6/http';
import { check, fail } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'https://replant.kro.kr';
export const BUYERS = Number(__ENV.BUYERS || 2000); // perf/data/seed.sql의 buyers
export const SELLERS = Number(__ENV.SELLERS || 200);
const PASSWORD = __ENV.PERF_PASSWORD; // 테스트 계정 비밀번호 (레포에 두지 않음, run.sh가 넣어 줌)

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function requirePassword() {
  if (!PASSWORD) fail('PERF_PASSWORD가 없음: perf/k6/run.sh로 실행할 것');
}

export function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

export function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

// 요청 이름(tags.name)을 경로 모양으로 묶어야 Grafana · k6 결과가 상품 id마다 흩어지지 않는다
export function get(path, token, name) {
  return http.get(`${BASE_URL}${path}`, { headers: authHeaders(token), tags: { name: name || path } });
}

export function post(path, body, token, name) {
  return http.post(`${BASE_URL}${path}`, JSON.stringify(body), {
    headers: { ...JSON_HEADERS, ...authHeaders(token) },
    tags: { name: name || path },
  });
}

function authHeaders(token) {
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export function login(email) {
  const res = post('/api/v1/auth/login', { email, password: PASSWORD }, null, 'POST /auth/login');
  const ok = check(res, { '로그인 200': (r) => r.status === 200 });
  return ok ? res.json('data.accessToken') : null;
}

export function buyerEmail(n) {
  return `perf-buyer-${n}@perf.test`;
}

export function sellerEmail(n) {
  return `perf-seller-${n}@perf.test`;
}
