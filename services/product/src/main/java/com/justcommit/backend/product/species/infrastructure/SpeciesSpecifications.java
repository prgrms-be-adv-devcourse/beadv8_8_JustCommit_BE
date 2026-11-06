package com.justcommit.backend.product.species.infrastructure;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.domain.enums.SpeciesCategory;

public class SpeciesSpecifications {

    public static Specification<Species> searchCondition(
            String keyword, SpeciesCategory category) {
        List<Specification<Species>> specs = new ArrayList<>();

        if (StringUtils.hasText(keyword)) {
            specs.add((root, query, cb) -> cb.like(root.get("name"), "%" + keyword.trim() + "%"));
        }
        if(category!=null){
            specs.add((root, query, cb) -> cb.equal(root.get("category"), category));
        }

        //AND 로 묶어서 변환
        return Specification.allOf(specs);
    }
}
