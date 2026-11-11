package com.justcommit.backend.product.banned.infrastructure;

import com.justcommit.backend.product.banned.application.BannedListService;
import com.justcommit.backend.product.banned.domain.BannedKeyword;
import com.justcommit.backend.product.banned.domain.BannedSpecies;
import com.justcommit.backend.product.banned.domain.BannedTextMatcher;
import com.justcommit.backend.product.banned.domain.ScientificNameMatcher;
import com.justcommit.backend.product.banned.domain.enums.BannedBasis;
import com.justcommit.backend.product.banned.domain.enums.BannedKeywordType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// Flyway V7 · V8로 판매 금지 목록이 들어가는지 (#342 완료 기준)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(BannedListService.class)
@Testcontainers(disabledWithoutDocker = true)
class BannedListDataTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired BannedSpeciesRepository bannedSpeciesRepository;
    @Autowired BannedKeywordRepository bannedKeywordRepository;
    @Autowired BannedListService bannedListService;

    @Test
    @DisplayName("금지 품종 118줄(Ⅰ급 13 · Ⅱ급 79 · 마약류 8 · 생태계교란 18) · 별칭 22개가 들어간다")
    void bannedSpeciesLoaded() {
        List<BannedSpecies> all = bannedSpeciesRepository.findAllWithAliases();

        assertThat(all).hasSize(118);
        assertThat(all.stream().collect(Collectors.groupingBy(BannedSpecies::getBasis, Collectors.counting())))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        BannedBasis.KR_ENDANGERED_I, 13L, BannedBasis.KR_ENDANGERED_II, 79L,
                        BannedBasis.NARCOTIC, 8L, BannedBasis.INVASIVE, 18L));
        assertThat(all.stream().mapToInt(s -> s.getAliases().size()).sum()).isEqualTo(22);
    }

    @Test
    @DisplayName("금지어 12개: 반려 4 · 관리자 확인 1(자생지) · 예외 7")
    void keywordsLoaded() {
        Map<BannedKeywordType, Long> counts = bannedKeywordRepository.findAll().stream()
                .collect(Collectors.groupingBy(BannedKeyword::getKeywordType, Collectors.counting()));

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(
                BannedKeywordType.BANNED, 4L, BannedKeywordType.REVIEW, 1L, BannedKeywordType.EXCEPTION, 7L));
    }

    @Test
    @DisplayName("실제 목록으로 학명 · 글을 대조한다")
    void matchWithRealData() {
        assertThat(bannedListService.matchScientificName("Phalaenopsis japonica").koreanName()).isEqualTo("나도풍란");
        assertThat(bannedListService.matchScientificName("Phalaenopsis").result())
                .isEqualTo(ScientificNameMatcher.Result.NONE);
        assertThat(bannedListService.matchText("꽃양귀비 모종", "베란다에서 키웠어요").result())
                .isEqualTo(BannedTextMatcher.Result.NONE);
        assertThat(bannedListService.matchText("양귀비 팝니다", null).result())
                .isEqualTo(BannedTextMatcher.Result.BANNED);
    }
}
