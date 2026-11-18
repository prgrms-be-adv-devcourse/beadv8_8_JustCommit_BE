package com.justcommit.backend;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
// 앱 전체를 띄우면 스케줄러도 돈다. 공유 DB를 건드리지 않게 끝나면 컨텍스트를 닫는다
@DirtiesContext
class MemberApplicationTests {

    // 테스트할 때마다 새 RSA 키 쌍을 만들어 개인키 · 공개키를 함께 넣는다 (키를 파일에 남기지 않음)
    private static final KeyPair JWT_KEY_PAIR = newKeyPair();

    @DynamicPropertySource
    static void jwtKeys(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
        registry.add("jwt.private-key", () -> encode(JWT_KEY_PAIR.getPrivate().getEncoded()));
        registry.add("jwt.public-key", () -> encode(JWT_KEY_PAIR.getPublic().getEncoded()));
    }

    @Test
    @DisplayName("V1 · V2(Outbox) · V3(인증 버전) · V4(토큰 폐기 작업) · V5(재설정 폐기 사유) · V6(계좌 HMAC) · V7(판매자 검수 동의) 마이그레이션이 적용되고 엔티티와 테이블이 맞는다 (ddl-auto: validate 통과)")
    void contextLoads(@Autowired Flyway flyway) {
        assertThat(flyway.info().applied()).extracting(m -> m.getVersion().getVersion()).containsExactly("1", "2", "3", "4", "5", "6", "7");
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
