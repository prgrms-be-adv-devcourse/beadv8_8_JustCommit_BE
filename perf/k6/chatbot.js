// 챗봇 대표 질문 18개(#303)를 회원 여러 명이 차례로 물어 단계별 시간(#634)을 모은다
// Gateway가 회원당 1분 10회(6초에 1개)로 제한하므로 한 회원은 GAP초(기본 7초)마다 1개만 보낸다
// 회원 MEMBERS명(기본 3) × 18개 = 54개, 약 2분 10초. 답변 생성(Claude)까지 재려면 운영 ai에 ANTHROPIC_API_KEY가 있어야 한다
//   (없으면 answerGenerated=false로 답하고 answer 단계가 남지 않음 → 요약의 「답변 생성됨」 비율로 확인)
// 실행: perf/k6/run.sh chatbot.js -e MEMBERS=3   (구매자 perf-buyer-1..N 로그인, 비밀번호는 run.sh가 넣음)
//   측정 계정이 없으면 회원 1명으로: EMAIL=<내 계정> PERF_PASSWORD=<비밀번호> k6 run -e ROUNDS=2 chatbot.js
//   (MEMBERS는 1로 고정, ROUNDS번 18개를 되풀이. 그 계정에 대화 기록이 남음)
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { buyerEmail, login, post, requirePassword } from './lib/common.js';
import { CHATBOT_QUESTIONS } from './lib/search-stages-data.js';

export { handleSummary } from './lib/common.js'; // 결과 파일에서 로그인 토큰 빼기

const EMAIL = __ENV.EMAIL;
const MEMBERS = EMAIL ? 1 : Number(__ENV.MEMBERS || 3);
const ROUNDS = Number(__ENV.ROUNDS || 1);
const GAP = Number(__ENV.GAP || 7);
const generated = new Rate('chatbot_answer_generated');

export const options = {
  scenarios: {
    chatbot: { executor: 'per-vu-iterations', vus: MEMBERS, iterations: ROUNDS, maxDuration: '20m' },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// 미리 로그인 (Gateway 로그인 제한: 같은 IP 1분 5회 → MEMBERS가 5를 넘으면 LOGIN_BASE_URL로, perf/k6/README.md)
export function setup() {
  requirePassword();
  const tokens = [];
  for (let n = 1; n <= MEMBERS; n++) {
    const email = EMAIL || buyerEmail(n);
    const token = login(email);
    if (!token) throw new Error(EMAIL ? '로그인 실패: EMAIL 계정' : `로그인 실패: ${email}`);
    tokens.push(token);
  }
  return { tokens };
}

export default function (data) {
  const token = data.tokens[__VU - 1];
  let conversationId = null;
  for (const q of CHATBOT_QUESTIONS) {
    const body = q.followUp && conversationId ? { conversationId, message: q.text } : { message: q.text };
    const res = post('/api/v1/chatbot/messages', body, token, 'POST /chatbot/messages');
    const ok = check(res, { '200': (r) => r.status === 200, '429 아님': (r) => r.status !== 429 });
    if (ok) {
      conversationId = res.json('data.conversationId');
      generated.add(res.json('data.answerGenerated') === true);
    }
    sleep(GAP);
  }
}
