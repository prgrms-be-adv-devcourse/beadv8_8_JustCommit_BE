package com.justcommit.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class ProductApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    // 테스트할 때마다 새 RSA 키를 만들어 공개키만 넣는다 (키를 파일에 남기지 않음)
    @DynamicPropertySource
    static void jwtPublicKey(DynamicPropertyRegistry registry) {
        registry.add("jwt.public-key", ProductApplicationTests::newPublicKey);
        // 앱이 뜨는지만 보는 테스트라 로컬 Kafka의 이벤트를 실제로 읽지 않게 (읽으면 product 그룹의 읽은 위치가 기록됨)
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Test
    @DisplayName("빈 DB에서 Flyway V1 ~ V8이 적용되고 ddl-auto validate로 product 서비스가 뜬다")
    void contextLoads(@Autowired Flyway flyway, @Autowired JdbcTemplate jdbc) {
        assertThat(flyway.info().applied())
                .extracting(info -> info.getVersion().getVersion(), MigrationInfo::getState)
                .contains(tuple("1", MigrationState.SUCCESS), tuple("2", MigrationState.SUCCESS),
                        tuple("3", MigrationState.SUCCESS), tuple("4", MigrationState.SUCCESS),
                        tuple("5", MigrationState.SUCCESS));
        // V5가 회원 복제본 테이블을 만듦 (없으면 이 조회가 실패)
        assertThat(jdbc.queryForObject("select count(*) from member_replica", Integer.class)).isZero();
    }

    @Test
    @DisplayName("V4가 품종 215종 · 사진 · 별칭을 넣고, 판매 금지로 뺀 나도풍란 · 파피오페딜럼은 없다")
    void speciesDataLoaded(@Autowired JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("select count(*) from species", Integer.class)).isEqualTo(215);
        assertThat(jdbc.queryForObject("select count(*) from species where id in (18, 178)", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from species where scientific_name is null", Integer.class)).isZero();
        // 이름 · 학명에 이중 공백이나 둥근 따옴표가 없어야 검색 · 학명 대조가 어긋나지 않는다
        assertThat(jdbc.queryForObject(
                "select count(*) from species where name like '%  %' or scientific_name like '%  %'"
                        + " or scientific_name ~ '[‘’]'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from species_image", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from species_alias", Integer.class)).isPositive();
    }

    @Test
    @DisplayName("품종 id를 직접 넣은 뒤에도 시퀀스가 맞춰져 있어 id 없이 넣으면 마지막 id 다음 번호를 받는다")
    void speciesSequenceAfterFixedIds(@Autowired JdbcTemplate jdbc) {
        Long nextId = jdbc.queryForObject(
                "insert into species (name) values ('시퀀스 확인용') returning id", Long.class);
        try {
            assertThat(nextId).isGreaterThan(217L);
        } finally {
            jdbc.update("delete from species where id = ?", nextId);
        }
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
