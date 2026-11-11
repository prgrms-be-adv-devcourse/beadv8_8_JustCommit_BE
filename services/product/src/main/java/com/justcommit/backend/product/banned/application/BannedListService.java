package com.justcommit.backend.product.banned.application;

import com.justcommit.backend.product.banned.domain.BannedKeyword;
import com.justcommit.backend.product.banned.domain.BannedSpecies;
import com.justcommit.backend.product.banned.domain.BannedSpeciesAlias;
import com.justcommit.backend.product.banned.domain.BannedTextMatcher;
import com.justcommit.backend.product.banned.domain.ScientificNameMatcher;
import com.justcommit.backend.product.banned.domain.enums.BannedAliasType;
import com.justcommit.backend.product.banned.infrastructure.BannedKeywordRepository;
import com.justcommit.backend.product.banned.infrastructure.BannedSpeciesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

// 판매 금지 목록 대조 (사진 검수 #343에서 사용)
// 목록은 Flyway로만 바뀌고 바뀌면 재배포하므로, 처음 쓸 때 한 번 읽어 대조기를 만들어 두고 계속 쓴다
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BannedListService {

    private final BannedSpeciesRepository bannedSpeciesRepository;
    private final BannedKeywordRepository bannedKeywordRepository;

    private volatile Matchers matchers;

    // 엔진이 답한 학명 하나를 금지 목록(법령 학명 · 학명 동의어)과 대조
    public ScientificNameMatcher.Match matchScientificName(String candidate) {
        return matchers().scientific().match(candidate);
    }

    // 제목 · 설명을 금지 품종 한글 이름 · 별칭 · 금지어와 대조
    public BannedTextMatcher.Match matchText(String title, String description) {
        return matchers().text().match(title, description);
    }

    // 여러 스레드가 처음에 동시에 불러도 한 번만 읽도록
    private Matchers matchers() {
        Matchers loaded = matchers;
        if (loaded == null) {
            synchronized (this) {
                loaded = matchers;
                if (loaded == null) {
                    loaded = load();
                    matchers = loaded;
                }
            }
        }
        return loaded;
    }

    private Matchers load() {
        List<ScientificNameMatcher.Entry> entries = new ArrayList<>();
        List<String> banned = new ArrayList<>();
        for (BannedSpecies s : bannedSpeciesRepository.findAllWithAliases()) {
            entries.add(new ScientificNameMatcher.Entry(s.getScientificName(), s.getScientificName(), s.getKoreanName()));
            banned.add(s.getKoreanName());
            for (BannedSpeciesAlias a : s.getAliases()) {
                banned.add(a.getAlias());
                if (a.getAliasType() == BannedAliasType.SCIENTIFIC) {
                    entries.add(new ScientificNameMatcher.Entry(a.getAlias(), s.getScientificName(), s.getKoreanName()));
                }
            }
        }
        List<String> review = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        for (BannedKeyword k : bannedKeywordRepository.findAll()) {
            switch (k.getKeywordType()) {
                case BANNED -> banned.add(k.getKeyword());
                case REVIEW -> review.add(k.getKeyword());
                case EXCEPTION -> exceptions.add(k.getKeyword());
            }
        }
        return new Matchers(new ScientificNameMatcher(entries), new BannedTextMatcher(banned, review, exceptions));
    }

    private record Matchers(ScientificNameMatcher scientific, BannedTextMatcher text) {
    }
}
