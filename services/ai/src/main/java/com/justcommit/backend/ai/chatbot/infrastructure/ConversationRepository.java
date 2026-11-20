package com.justcommit.backend.ai.chatbot.infrastructure;

import com.justcommit.backend.ai.chatbot.application.ChatTurn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// 대화 주인 · 대화 기록 (#302, JDBC)
@Repository
@RequiredArgsConstructor
public class ConversationRepository {

    private final JdbcClient jdbcClient;

    /** 대화 주인 회원 ID (없는 대화면 빈 값) */
    public Optional<Long> findOwner(UUID conversationId) {
        return jdbcClient.sql("SELECT member_id FROM chat_conversation WHERE id = :id")
                .param("id", conversationId)
                .query(Long.class)
                .optional();
    }

    public void create(UUID conversationId, long memberId) {
        jdbcClient.sql("INSERT INTO chat_conversation (id, member_id) VALUES (:id, :memberId)")
                .param("id", conversationId)
                .param("memberId", memberId)
                .update();
    }

    /** 최근 limit개 메시지를 오래된 순서로 */
    public List<ChatTurn> findRecentTurns(UUID conversationId, int limit) {
        List<ChatTurn> turns = new ArrayList<>(jdbcClient.sql("""
                        SELECT role, content FROM chat_message
                        WHERE conversation_id = :id
                        ORDER BY id DESC
                        LIMIT :limit
                        """)
                .param("id", conversationId)
                .param("limit", limit)
                .query((rs, rowNum) -> new ChatTurn(ChatTurn.Role.valueOf(rs.getString("role")), rs.getString("content")))
                .list());
        Collections.reverse(turns);
        return turns;
    }

    /** 질문 · 답변 한 쌍을 저장하고 대화의 마지막 시각을 바꾼다 */
    public void appendExchange(UUID conversationId, String question, String answer) {
        jdbcClient.sql("""
                        INSERT INTO chat_message (conversation_id, role, content)
                        VALUES (:id, 'USER', :question), (:id, 'ASSISTANT', :answer)
                        """)
                .param("id", conversationId)
                .param("question", question)
                .param("answer", answer)
                .update();
        jdbcClient.sql("UPDATE chat_conversation SET updated_at = now() WHERE id = :id")
                .param("id", conversationId)
                .update();
    }
}
