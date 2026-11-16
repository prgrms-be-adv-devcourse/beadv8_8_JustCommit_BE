package com.justcommit.backend.ai.species;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// 품종 프로필 시드(V3)가 product 품종 기준 데이터(V4)와 같은 ID · 이름인지 확인 (ADR 0020)
@Testcontainers
class SpeciesProfileSeedTest {

    // product V4: 품종 id는 고정값, 18 · 178은 빠짐. 「기타」 218은 V11이라 V4에 없다
    private static final Path PRODUCT_SPECIES_SQL =
            Path.of("../product/src/main/resources/db/migration/V4__insert_species_data.sql");
    private static final Pattern SPECIES_ROW = Pattern.compile("^\\s*\\((\\d+), '((?:[^']|'')*)',");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    @DisplayName("빈 DB에 Flyway를 적용하면 species_profile이 215행이고 18 · 178 · 218이 없다")
    void seedHas215Species() throws SQLException {
        Map<Integer, String> profiles = loadProfiles();

        assertThat(profiles).hasSize(215);
        assertThat(profiles.keySet()).doesNotContain(18, 178, 218);
    }

    @Test
    @DisplayName("시드의 품종 ID · 이름이 product V4 species와 모두 같다")
    void seedMatchesProductSpecies() throws SQLException, IOException {
        assertThat(loadProfiles()).containsExactlyInAnyOrderEntriesOf(loadProductSpecies());
    }

    private static Map<Integer, String> loadProfiles() throws SQLException {
        Map<Integer, String> profiles = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT species_id, name FROM species_profile")) {
            while (rs.next()) {
                profiles.put(rs.getInt("species_id"), rs.getString("name"));
            }
        }
        return profiles;
    }

    // V4의 species INSERT 부분만 읽는다 (species_image · species_alias 줄은 품종 이름으로 시작해 패턴에 안 걸림)
    private static Map<Integer, String> loadProductSpecies() throws IOException {
        Map<Integer, String> species = new LinkedHashMap<>();
        for (String line : Files.readAllLines(PRODUCT_SPECIES_SQL)) {
            Matcher matcher = SPECIES_ROW.matcher(line);
            if (matcher.find()) {
                species.put(Integer.parseInt(matcher.group(1)), matcher.group(2).replace("''", "'"));
            }
        }
        assertThat(species).as("product V4 species 행").hasSize(215);
        return species;
    }
}
