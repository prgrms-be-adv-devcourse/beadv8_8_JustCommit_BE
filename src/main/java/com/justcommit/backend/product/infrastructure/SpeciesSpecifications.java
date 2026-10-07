package com.justcommit.backend.product.infrastructure;

import com.justcommit.backend.product.domain.Species;
import com.justcommit.backend.product.domain.enums.Difficulty;
import com.justcommit.backend.product.domain.enums.Light;
import com.justcommit.backend.product.domain.enums.SpeciesCategory;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class SpeciesSpecifications {

    public static Specification<Species> searchCondition(
            String keyword, SpeciesCategory category, Light light, Difficulty difficulty) {
        List<Specification<Species>> specs = new ArrayList<>();
        specs.add(((root, query, cb) -> cb.isFalse(root.get("banded"))));

        if (StringUtils.hasText(keyword)) {
            specs.add((root, query, cb) -> cb.like(root.get("name"), "%" + keyword.trim() + "%"));
        }
        if(category!=null){
            specs.add((root, query, cb) -> cb.equal(root.get("category"), category));
        }
        if (light != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("light"), light));
        }
        if (difficulty != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("difficulty"), difficulty));
        }
        //AND 로 묶어서 변환
        return Specification.allOf(specs);
    }
}
