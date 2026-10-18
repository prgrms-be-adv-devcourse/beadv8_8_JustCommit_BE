package com.justcommit.backend.notification.infrastructure.sse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationSseRegistryTest {

    private static final Long BUYER = 10L;
    private static final Long OTHER = 99L;

    private final NotificationSseRegistry registry = new NotificationSseRegistry();

    @Test
    @DisplayName("연결하자마자 지금 안 읽은 알림 수를 보낸다")
    void sendsUnreadCountOnConnect() {
        RecordingEmitter emitter = new RecordingEmitter();

        registry.add(BUYER, emitter, 3);

        assertThat(emitter.sent).containsExactly("event:unread\ndata:3\n\n");
    }

    @Test
    @DisplayName("한 회원의 탭 두 개 모두에 보내고, 다른 회원에게는 보내지 않는다")
    void sendsToAllTabsOfMember() {
        RecordingEmitter tab1 = new RecordingEmitter();
        RecordingEmitter tab2 = new RecordingEmitter();
        RecordingEmitter other = new RecordingEmitter();
        registry.add(BUYER, tab1, 0);
        registry.add(BUYER, tab2, 0);
        registry.add(OTHER, other, 0);

        registry.sendUnreadCount(BUYER, 1);

        assertThat(tab1.sent).last().isEqualTo("event:unread\ndata:1\n\n");
        assertThat(tab2.sent).last().isEqualTo("event:unread\ndata:1\n\n");
        assertThat(other.sent).hasSize(1); // 처음 연결 때 받은 것뿐
    }

    @Test
    @DisplayName("끊긴 연결은 보내다 실패하면 목록에서 뺀다")
    void removesBrokenConnection() {
        RecordingEmitter emitter = new RecordingEmitter();
        registry.add(BUYER, emitter, 0);
        emitter.broken = true;

        registry.sendUnreadCount(BUYER, 1);

        assertThat(registry.hasConnection(BUYER)).isFalse();
    }

    @Test
    @DisplayName("하트비트는 주석 한 줄을 보낸다")
    void heartbeatSendsComment() {
        RecordingEmitter emitter = new RecordingEmitter();
        registry.add(BUYER, emitter, 0);

        registry.heartbeat();

        assertThat(emitter.sent).last().isEqualTo(":ping\n\n");
    }

    @Test
    @DisplayName("연결이 없는 회원에게 보내도 아무 일도 일어나지 않는다")
    void sendWithoutConnectionDoesNothing() {
        registry.sendUnreadCount(BUYER, 1);

        assertThat(registry.hasConnection(BUYER)).isFalse();
    }

    // 실제로 보내는 대신 보낸 내용을 기록하는 연결
    static class RecordingEmitter extends SseEmitter {
        final List<String> sent = new ArrayList<>();
        boolean broken;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (broken) {
                throw new IOException("브라우저가 연결을 끊음");
            }
            sent.add(builder.build().stream()
                    .map(data -> String.valueOf(data.getData()))
                    .collect(Collectors.joining()));
        }
    }
}
