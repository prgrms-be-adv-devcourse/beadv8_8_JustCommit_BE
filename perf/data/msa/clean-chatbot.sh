#!/usr/bin/env bash
# 챗봇 측정(#634, perf/k6/chatbot.js)이 운영 ai_db에 남긴 측정 계정의 대화 기록을 지운다
#
# 사용 (내 PC, ssh replant-data가 비밀번호 없이 되어야 함):
#   perf/data/msa/clean-chatbot.sh          # 지울 개수만 보여 줌
#   perf/data/msa/clean-chatbot.sh --yes    # 실제로 지움
#
# 측정 계정(perf-buyer-*@perf.test)의 회원 번호를 member_db에서 읽어 ai_db의 chat_conversation을 지운다.
# chat_message는 FK ON DELETE CASCADE로 같이 지워진다. 실제 회원의 대화는 건드리지 않는다.
set -euo pipefail

DATA=${DATA_HOST:-replant-data}
psql_db() { ssh -o BatchMode=yes "$DATA" "docker exec -i replant-postgres sh -c 'psql -U \$POSTGRES_USER -d $1 -v ON_ERROR_STOP=1 -q -At'"; }

ids=$(psql_db member_db <<<"SELECT string_agg(id::text, ',') FROM member WHERE email LIKE 'perf-buyer-%@perf.test';")
[ -n "$ids" ] || { echo "측정 계정이 없음 (perf/data/msa/seed.sh로 넣은 뒤 측정)"; exit 0; }

count=$(psql_db ai_db <<<"SELECT count(*) || ' 대화 · ' || (SELECT count(*) FROM chat_message m JOIN chat_conversation c ON c.id = m.conversation_id WHERE c.member_id IN ($ids)) || ' 메시지' FROM chat_conversation WHERE member_id IN ($ids);")
echo "측정 계정 대화: $count"
if [ "${1:-}" != "--yes" ]; then
  echo "지우려면 --yes"
  exit 0
fi
psql_db ai_db <<<"DELETE FROM chat_conversation WHERE member_id IN ($ids);"
echo "지움"
