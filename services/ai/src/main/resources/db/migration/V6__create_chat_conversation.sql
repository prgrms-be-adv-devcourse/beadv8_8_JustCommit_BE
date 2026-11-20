-- 챗봇 대화 기록 (#302, ADR 0031)
-- 대화 주인: conversationId를 처음 발급한 회원만 이어 쓸 수 있다 (다른 회원 403, 없는 대화 404)
CREATE TABLE chat_conversation (
    id         UUID        PRIMARY KEY,
    member_id  BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()  -- 마지막 메시지 시각 (오래된 대화 정리 기준, 정리 배치는 ⬜)
);

-- 질문 · 답변을 한 쌍씩 저장한다. 프롬프트에는 최근 10개(5쌍)만 넣는다
CREATE TABLE chat_message (
    id              BIGSERIAL   PRIMARY KEY,
    conversation_id UUID        NOT NULL REFERENCES chat_conversation (id) ON DELETE CASCADE,
    role            VARCHAR(10) NOT NULL,  -- USER · ASSISTANT
    content         TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX chat_message_conversation_idx ON chat_message (conversation_id, id);
