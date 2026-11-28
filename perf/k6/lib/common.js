// 부하 측정 공통 (#351) — 로그인 · 요청 헤더 · 무작위 고르기
import http from 'k6/http';
import { check, fail } from 'k6';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

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

// MSA(#628): Gateway가 로그인을 같은 IP 연속 5회로 제한한다. 미리 로그인만 SSH 터널로 Gateway에 직접 보내고(LOGIN_BASE_URL,
// 예: http://localhost:18080 → 앱 서버 NodePort 30080), 계정마다 다른 X-Forwarded-For를 붙인다. Nginx를 거치는 공개 주소는
// X-Forwarded-For를 접속 IP로 덮어써서 이 방법이 통하지 않는다. 측정 요청은 그대로 BASE_URL로 간다
const LOGIN_BASE_URL = __ENV.LOGIN_BASE_URL;

function fakeIp(email) {
  let h = 0;
  for (let i = 0; i < email.length; i++) h = (h * 31 + email.charCodeAt(i)) >>> 0;
  return `10.250.${(h >>> 8) & 255}.${h & 255}`;
}

// 여러 명을 http.batch로 미리 로그인하는 스크립트(order · mixed · concurrent · mypage)용
export const LOGIN_URL = `${LOGIN_BASE_URL || BASE_URL}/api/v1/auth/login`;
export function loginHeaders(email) {
  return LOGIN_BASE_URL ? { ...JSON_HEADERS, 'X-Forwarded-For': fakeIp(email) } : JSON_HEADERS;
}

export function login(email) {
  const res = LOGIN_BASE_URL
    ? http.post(`${LOGIN_BASE_URL}/api/v1/auth/login`, JSON.stringify({ email, password: PASSWORD }), {
        headers: { ...JSON_HEADERS, 'X-Forwarded-For': fakeIp(email) },
        tags: { name: 'setup' },
      })
    : post('/api/v1/auth/login', { email, password: PASSWORD }, null, 'POST /auth/login');
  const ok = check(res, { '로그인 200': (r) => r.status === 200 });
  if (!ok) {
    let code = '';
    try { code = res.json('code') || res.json('error.code') || ''; } catch (e) { code = '(JSON 아님)'; }
    console.warn(`로그인 실패: HTTP ${res.status} ${code}`);
  }
  return ok ? res.json('data.accessToken') : null;
}

export function buyerEmail(n) {
  return `perf-buyer-${n}@perf.test`;
}

export function sellerEmail(n) {
  return `perf-seller-${n}@perf.test`;
}

// setup()이 돌려준 값(로그인 토큰)이 --summary-export 결과 파일에 들어가지 않게 뺀다 (#634).
// 스크립트에서 `export { handleSummary } from './lib/common.js'`로 쓴다
export function handleSummary(data) {
  delete data.setup_data;
  return { stdout: textSummary(data, { indent: ' ', enableColors: true }) };
}
