package com.justcommit.backend;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class MemberApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    // 테스트할 때마다 새 RSA 키 쌍을 만들어 개인키 · 공개키를 함께 넣는다 (키를 파일에 남기지 않음)
    private static final KeyPair JWT_KEY_PAIR = newKeyPair();

    @DynamicPropertySource
    static void jwtKeys(DynamicPropertyRegistry registry) {
        registry.add("jwt.private-key", () -> encode(JWT_KEY_PAIR.getPrivate().getEncoded()));
        registry.add("jwt.public-key", () -> encode(JWT_KEY_PAIR.getPublic().getEncoded()));
    }

    @Test
    @DisplayName("V1 · V2(Outbox) · V3(인증 버전) 마이그레이션이 적용되고 엔티티와 테이블이 맞는다 (ddl-auto: validate 통과)")
    void contextLoads(@Autowired Flyway flyway) {
        assertThat(flyway.info().applied()).extracting(m -> m.getVersion().getVersion()).containsExactly("1", "2", "3");
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(byte[] der) {
        return Base64.getEncoder().encodeToString(der);
    }
}
