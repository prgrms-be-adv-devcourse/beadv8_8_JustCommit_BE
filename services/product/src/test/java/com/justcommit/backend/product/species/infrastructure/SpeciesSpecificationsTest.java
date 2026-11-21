package com.justcommit.backend.product.species.infrastructure;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.product.config.ClockConfig;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.domain.SpeciesAlias;
import com.justcommit.backend.product.species.domain.enums.AliasSource;
import com.justcommit.backend.product.species.domain.enums.SpeciesCategory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import com.justcommit.backend.product.species.presentation.dto.SpeciesSearchRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class SpeciesSpecificationsTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private SpeciesRepository speciesRepository;

    @BeforeEach
    void setUp() {
        Species donnamu = species("돈나무", "Pittosporum tobira", SpeciesCategory.FOLIAGE);
        Species zamia = species("금전수", "Zamioculcas zamiifolia", SpeciesCategory.FOLIAGE);
        species("드라세나 '자바'", "Dracaena angustifolia", SpeciesCategory.FOLIAGE);
        species("톨미아", "Tolmiea menziesii", SpeciesCategory.FOLIAGE);
        alias(zamia, "돈나무", AliasSource.NONGSARO);
        alias(zamia, "자미오쿨카스", AliasSource.NONGSARO);
        alias(donnamu, "섬엄나무", AliasSource.MANUAL);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("품종명과 별칭이 같은 검색어에 걸리면 두 품종을 모두 반환")
    void nameAndAliasTogether() {
        assertThat(search("돈나무", null)).containsExactly("금전수", "돈나무");
    }

    @Test
    @DisplayName("별칭으로만 검색해도 그 별칭을 가진 품종을 반환")
    void aliasOnly() {
        assertThat(search("자미오", null)).containsExactly("금전수");
    }

    @Test
    @DisplayName("별칭이 없는 품종도 품종명으로 검색")
    void speciesWithoutAlias() {
        assertThat(search("톨미아", null)).containsExactly("톨미아");
    }

    @Test
    @DisplayName("띄어쓰기와 따옴표를 무시하고 검색")
    void ignoreSpacesAndQuotes() {
        assertThat(search("드라세나자바", null)).containsExactly("드라세나 '자바'");
        assertThat(search("세나 '자", null)).containsExactly("드라세나 '자바'");
    }

    @Test
    @DisplayName("학명은 대소문자 구분 없이 검색")
    void scientificName() {
        assertThat(search("zamioculcas", null)).containsExactly("금전수");
    }

    @Test
    @DisplayName("품종명과 별칭이 함께 걸려도 품종은 한 번만 반환")
    void noDuplicates() {
        // 돈나무: 품종명 "돈나무"와 별칭 "섬엄나무"가 모두 걸림
        assertThat(search("나무", null)).containsExactly("금전수", "돈나무");
    }

    @Test
    @DisplayName("카테고리 조건은 별칭 검색과 AND로 묶임")
    void categoryWithAlias() {
        assertThat(search("돈나무", SpeciesCategory.SUCCULENT)).isEmpty();
    }

    @Test
    @DisplayName("키워드 없이 찾으면 글자 수와 상관없이 가나다순")
    void koreanOrderWithoutKeyword() {
        species("호야", null, SpeciesCategory.FOLIAGE);
        species("개운죽", null, SpeciesCategory.FOLIAGE);
        species("남천", null, SpeciesCategory.FOLIAGE);
        entityManager.flush();
        entityManager.clear();

        assertThat(search(null, null)).containsExactly(
                "개운죽", "금전수", "남천", "돈나무", "드라세나 '자바'", "톨미아", "호야");
    }

    @Test
    @DisplayName("「기타」는 가나다순과 상관없이 맨 뒤")
    void otherSpeciesLast() {
        insertOtherSpecies();

        assertThat(search(null, null)).containsExactly(
                "금전수", "돈나무", "드라세나 '자바'", "톨미아", "기타");
    }

    @Test
    @DisplayName("목록 API 페이지 요청(sort 없음)에서도 가나다순 · 「기타」 맨 뒤 정렬이 유지됨")
    void orderKeptWithPageRequest() {
        insertOtherSpecies();
        SpeciesSearchRequest request = new SpeciesSearchRequest(null, null, null, null);

        List<String> names = speciesRepository.findAll(
                        SpeciesSpecifications.searchCondition(null, null), request.toPageable())
                .map(Species::getName).getContent();

        assertThat(names).containsExactly("금전수", "돈나무", "드라세나 '자바'", "톨미아", "기타");
    }

    private void insertOtherSpecies() {
        entityManager.createNativeQuery(
                        "INSERT INTO species (id, name, created_at, updated_at) VALUES (:id, '기타', now(), now())")
                .setParameter("id", Species.OTHER_ID)
                .executeUpdate();
    }

    private List<String> search(String keyword, SpeciesCategory category) {
        return speciesRepository.findAll(SpeciesSpecifications.searchCondition(keyword, category))
                .stream().map(Species::getName).toList();
    }

    private Species species(String name, String scientificName, SpeciesCategory category) {
        Species species = BeanUtils.instantiateClass(Species.class);
        ReflectionTestUtils.setField(species, "name", name);
        ReflectionTestUtils.setField(species, "scientificName", scientificName);
        ReflectionTestUtils.setField(species, "category", category);
        entityManager.persist(species);
        return species;
    }

    private void alias(Species species, String value, AliasSource source) {
        SpeciesAlias alias = BeanUtils.instantiateClass(SpeciesAlias.class);
        ReflectionTestUtils.setField(alias, "species", species);
        ReflectionTestUtils.setField(alias, "alias", value);
        ReflectionTestUtils.setField(alias, "source", source);
        entityManager.persist(alias);
    }
}
