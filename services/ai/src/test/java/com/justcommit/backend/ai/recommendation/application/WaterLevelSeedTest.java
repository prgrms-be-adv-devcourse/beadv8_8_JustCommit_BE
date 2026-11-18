package com.justcommit.backend.ai.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.justcommit.backend.common.testcontainers.SharedPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

// 시드(V3)의 봄 물주기 원문이 모두 EnvironmentMatch.waterLevel의 단계로 바뀌는지 확인 (#507 리뷰)
// 시드 문구가 바뀌면 waterLevel이 조용히 null(중립 0.5)이 되므로, 여기서 실패해 알아채게 한다
@Testcontainers
class WaterLevelSeedTest {

    private static final String DB_URL = SharedPostgres.createDatabase();

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(DB_URL, SharedPostgres.container().getUsername(), SharedPostgres.container().getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    @DisplayName("봄 물주기 값이 있는 품종은 모두 물주기 단계로 바뀐다 (모르는 원문 없음)")
    void everySeedSpringWaterHasLevel() throws SQLException {
        List<String> waters = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                        DB_URL, SharedPostgres.container().getUsername(), SharedPostgres.container().getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT DISTINCT water_spring FROM species_profile WHERE water_spring IS NOT NULL")) {
            while (rs.next()) {
                waters.add(rs.getString(1));
            }
        }

        assertThat(waters).isNotEmpty();
        assertThat(waters).allSatisfy(water ->
                assertThat(EnvironmentMatch.waterLevel(water)).as("물주기 원문: %s", water).isNotNull());
    }
}
