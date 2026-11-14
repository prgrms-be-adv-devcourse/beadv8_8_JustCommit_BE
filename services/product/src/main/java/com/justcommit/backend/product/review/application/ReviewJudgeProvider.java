package com.justcommit.backend.product.review.application;

import com.justcommit.backend.product.banned.application.BannedListService;
import com.justcommit.backend.product.review.domain.ReviewJudge;
import com.justcommit.backend.product.review.domain.SpeciesMatcher;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.domain.SpeciesAlias;
import com.justcommit.backend.product.species.infrastructure.SpeciesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 판정기를 처음 쓸 때 한 번 만들어 계속 씀
// 품종 · 별칭 · 금지 목록은 Flyway로만 바뀌고 바뀌면 재배포하므로 (BannedListService와 같은 방식)
@Component
@RequiredArgsConstructor
public class ReviewJudgeProvider {

    private final SpeciesRepository speciesRepository;
    private final BannedListService bannedListService;

    private volatile ReviewJudge judge;

    public ReviewJudge get() {
        ReviewJudge loaded = judge;
        if (loaded == null) {
            synchronized (this) {
                loaded = judge;
                if (loaded == null) {
                    loaded = new ReviewJudge(bannedListService::matchScientificName, loadSpeciesMatcher());
                    judge = loaded;
                }
            }
        }
        return loaded;
    }

    // 별칭은 품종을 함께 읽으므로(JOIN FETCH) 트랜잭션 없이도 지연 로딩이 없음
    private SpeciesMatcher loadSpeciesMatcher() {
        Map<Long, List<String>> aliases = new HashMap<>();
        for (SpeciesAlias alias : speciesRepository.findAllAliasesWithSpecies()) {
            aliases.computeIfAbsent(alias.getSpecies().getId(), id -> new ArrayList<>()).add(alias.getAlias());
        }
        List<SpeciesMatcher.Entry> entries = new ArrayList<>();
        for (Species species : speciesRepository.findAll()) {
            entries.add(new SpeciesMatcher.Entry(species.getName(), species.getScientificName(),
                    aliases.getOrDefault(species.getId(), List.of())));
        }
        return new SpeciesMatcher(entries);
    }
}
