package com.justcommit.backend.product.species.infrastructure;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.domain.SpeciesAlias;
import com.justcommit.backend.product.species.domain.enums.SpeciesCategory;

public class SpeciesSpecifications {

    // 검색할 때 무시하는 문자: 띄어쓰기와 따옴표 ("드라세나 '자바'" ↔ "드라세나자바")
    private static final String[] IGNORED = {" ", "'", "\"", "‘", "’"};

    public static Specification<Species> searchCondition(
            String keyword, SpeciesCategory category) {
        List<Specification<Species>> specs = new ArrayList<>();

        if (StringUtils.hasText(keyword)) {
            specs.add(nameOrAliasContains(keyword));
        }
        if(category!=null){
            specs.add((root, query, cb) -> cb.equal(root.get("category"), category));
        }

        //AND 로 묶어서 변환
        return Specification.allOf(specs);
    }

    // 품종명, 학명, 별칭 중 하나라도 키워드를 포함하면 그 품종을 돌려준다 (species 먼저 찾고 멈추지 않음)
    private static Specification<Species> nameOrAliasContains(String keyword) {
        String pattern = "%" + normalize(keyword) + "%";
        return (root, query, cb) -> {
            Subquery<Long> alias = query.subquery(Long.class);
            Root<SpeciesAlias> a = alias.from(SpeciesAlias.class);
            alias.select(cb.literal(1L))
                    .where(cb.equal(a.get("species"), root),
                            cb.like(normalized(cb, a.get("alias")), pattern));

            return cb.or(
                    cb.like(normalized(cb, root.get("name")), pattern),
                    cb.like(normalized(cb, root.get("scientificName")), pattern),
                    cb.exists(alias));
        };
    }

    static String normalize(String value) {
        String result = value.trim().toLowerCase();
        for (String ignored : IGNORED) {
            result = result.replace(ignored, "");
        }
        return result;
    }

    private static Expression<String> normalized(CriteriaBuilder cb, Expression<String> column) {
        Expression<String> result = cb.lower(column);
        for (String ignored : IGNORED) {
            result = cb.function("replace", String.class, result, cb.literal(ignored), cb.literal(""));
        }
        return result;
    }
}
