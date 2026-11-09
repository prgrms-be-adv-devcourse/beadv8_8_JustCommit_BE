package com.justcommit.backend.notification.infrastructure.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// 회원별 SSE 연결 목록. 서버가 한 대라 메모리에 둔다. (여러 대가 되면 Redis Pub/Sub로 다른 서버에 알려야 함)
// 한 회원이 탭을 여러 개 열면 연결도 여러 개라 목록으로 관리한다.
@Slf4j
@Component
public class NotificationSseRegistry {

    private static final String UNREAD_EVENT = "unread";

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    // 연결을 등록하고, 지금 안 읽은 알림 수를 그 연결에만 바로 보낸다.
    public void add(Long memberId, SseEmitter emitter, long unreadCount) {
        emitters.compute(memberId, (id, list) -> {
            List<SseEmitter> connections = (list == null) ? new CopyOnWriteArrayList<>() : list;
            connections.add(emitter);
            return connections;
        });
        emitter.onCompletion(() -> remove(memberId, emitter));
        emitter.onTimeout(() -> {
            remove(memberId, emitter);
            emitter.complete(); // 클라이언트가 다시 연결한다
        });
        emitter.onError(error -> remove(memberId, emitter));

        send(memberId, emitter, unreadEvent(unreadCount));
    }

    public boolean hasConnection(Long memberId) {
        return emitters.containsKey(memberId);
    }

    // 그 회원의 모든 탭에 안 읽은 알림 수를 보낸다. 연결이 없으면 아무것도 하지 않는다.
    public void sendUnreadCount(Long memberId, long unreadCount) {
        for (SseEmitter emitter : emitters.getOrDefault(memberId, List.of())) {
            send(memberId, emitter, unreadEvent(unreadCount));
        }
    }

    // 할 말이 없어도 30초마다 주석 한 줄을 보내, 중간 장비(Nginx 등)가 조용한 연결을 끊지 않게 한다.
    @Scheduled(fixedRate = 30_000)
    public void heartbeat() {
        emitters.forEach((memberId, connections) ->
                connections.forEach(emitter -> send(memberId, emitter, SseEmitter.event().comment("ping"))));
    }

    private void send(Long memberId, SseEmitter emitter, SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            // 브라우저가 이미 연결을 끊음. 목록에서 빼고 넘어간다.
            log.debug("끊긴 SSE 연결 제거: memberId={}", memberId);
            remove(memberId, emitter);
        }
    }

    private void remove(Long memberId, SseEmitter emitter) {
        emitters.computeIfPresent(memberId, (id, connections) -> {
            connections.remove(emitter);
            return connections.isEmpty() ? null : connections;
        });
    }

    private SseEventBuilder unreadEvent(long unreadCount) {
        return SseEmitter.event().name(UNREAD_EVENT).data(unreadCount);
    }
}
