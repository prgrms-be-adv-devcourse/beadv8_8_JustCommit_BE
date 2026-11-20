package com.justcommit.backend.payment.infrastructure.messaging;

import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.common.testcontainers.SharedKafka;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.payment.infrastructure.repository.MemberReplicaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// 실제 Kafka, PostgreSQL로 member.events.v1 수신 → 회원 복제본, 지갑 생성을 확인한다 (설정은 application.yml 그대로)
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
// 리스너가 공유 Kafka의 다른 클래스 메시지를 읽지 않게 끝나면 컨텍스트를 닫는다
@DirtiesContext
class MemberEventKafkaIntegrationTest {

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
        SharedKafka.register(registry);
        registry.add("jwt.public-key", MemberEventKafkaIntegrationTest::newPublicKey);
        // application-test.yml은 리스너를 끄므로 이 클래스만 켠다
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
    }

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired MemberReplicaRepository memberReplicaRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("같은 MemberCreated 봉투를 두 번 받아도 복제본 1행, 회원 지갑 1개")
    void memberCreatedTwice() {
        String event = envelope("evt-9001", "MemberCreated", "2026-11-20T10:00:00+09:00",
                "{\"memberId\":9001,\"nickname\":\"초록손\",\"role\":\"MEMBER\",\"seller\":false}");
        kafkaTemplate.send(KafkaTopics.MEMBER_EVENTS, "9001", event);
        kafkaTemplate.send(KafkaTopics.MEMBER_EVENTS, "9001", event);
        // 같은 Key라 같은 파티션에서 차례로 처리: 뒤 메시지가 반영되면 앞 두 건도 처리된 것
        kafkaTemplate.send(KafkaTopics.MEMBER_EVENTS, "9001", envelope("evt-9001-u", "MemberUpdated",
                "2026-11-20T10:05:00+09:00",
                "{\"memberId\":9001,\"nickname\":\"새이름\",\"role\":\"MEMBER\",\"seller\":true}"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(memberReplicaRepository.findById(9001L))
                        .get().satisfies(replica -> assertThat(replica.getNickname()).isEqualTo("새이름")));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wallet where member_id = 9001", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("MemberWithdrawn을 받으면 복제본이 탈퇴 상태가 된다")
    void memberWithdrawn() {
        kafkaTemplate.send(KafkaTopics.MEMBER_EVENTS, "9002", envelope("evt-9002", "MemberWithdrawn",
                "2026-11-20T11:00:00+09:00", "{\"memberId\":9002}"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(memberReplicaRepository.findById(9002L))
                        .get().satisfies(replica -> assertThat(replica.isWithdrawn()).isTrue()));
    }

    private static String envelope(String eventId, String eventType, String occurredAt, String payload) {
        return """
                {"eventId":"%s","eventType":"%s","version":1,"occurredAt":"%s","aggregateId":"%s","payload":%s}
                """.formatted(eventId, eventType, occurredAt, eventId.replaceAll("\\D", ""), payload);
    }

    private static String newPublicKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
