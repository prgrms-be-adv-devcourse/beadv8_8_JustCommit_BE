package com.justcommit.backend.ai.species.infrastructure;

import com.justcommit.backend.ai.species.domain.SpeciesProfile;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// species_profile 조회 (JDBC). 데이터는 Flyway 시드로만 바뀌므로 쓰기 메서드가 없다
@Repository
@RequiredArgsConstructor
public class SpeciesProfileRepository {

    private final JdbcClient jdbcClient;

    public List<SpeciesProfile> findAll() {
        return jdbcClient.sql("""
                        SELECT species_id, nongsaro_no, name, scientific_name, genus, english_name,
                               distribution_name, family_name, category_names, leaf_color_names, difficulty,
                               light_names, light_min, light_max, placement_names,
                               growth_temp_name, growth_temp_min, growth_temp_max, winter_min_temp, humidity_name,
                               water_spring, water_summer, water_autumn, water_winter,
                               features, special_care, advice, pests, toxicity
                        FROM species_profile
                        ORDER BY species_id
                        """)
                .query(SpeciesProfile.class)
                .list();
    }

    // 추천 계산용: 기준 · 후보 품종만 (없는 ID는 빠짐)
    public List<SpeciesProfile> findAllById(Collection<Integer> speciesIds) {
        if (speciesIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                        SELECT species_id, nongsaro_no, name, scientific_name, genus, english_name,
                               distribution_name, family_name, category_names, leaf_color_names, difficulty,
                               light_names, light_min, light_max, placement_names,
                               growth_temp_name, growth_temp_min, growth_temp_max, winter_min_temp, humidity_name,
                               water_spring, water_summer, water_autumn, water_winter,
                               features, special_care, advice, pests, toxicity
                        FROM species_profile
                        WHERE species_id IN (:ids)
                        """)
                .param("ids", speciesIds)
                .query(SpeciesProfile.class)
                .list();
    }
}
