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
// 목록이 작아(118줄 · 금지어 12개) 부를 때마다 읽는다. 검수가 많아지면 캐시를 검토
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BannedListService {

    private final BannedSpeciesRepository bannedSpeciesRepository;
    private final BannedKeywordRepository bannedKeywordRepository;

    // 엔진이 답한 학명 하나를 금지 목록(법령 학명 · 학명 동의어)과 대조
    public ScientificNameMatcher.Match matchScientificName(String candidate) {
        List<ScientificNameMatcher.Entry> entries = new ArrayList<>();
        for (BannedSpecies s : bannedSpeciesRepository.findAllWithAliases()) {
            entries.add(new ScientificNameMatcher.Entry(s.getScientificName(), s.getScientificName(), s.getKoreanName()));
            for (BannedSpeciesAlias a : s.getAliases()) {
                if (a.getAliasType() == BannedAliasType.SCIENTIFIC) {
                    entries.add(new ScientificNameMatcher.Entry(a.getAlias(), s.getScientificName(), s.getKoreanName()));
                }
            }
        }
        return new ScientificNameMatcher(entries).match(candidate);
    }

    // 제목 · 설명을 금지 품종 한글 이름 · 별칭 · 금지어와 대조
    public BannedTextMatcher.Match matchText(String title, String description) {
        List<String> banned = new ArrayList<>();
        for (BannedSpecies s : bannedSpeciesRepository.findAllWithAliases()) {
            banned.add(s.getKoreanName());
            s.getAliases().forEach(a -> banned.add(a.getAlias()));
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
        return new BannedTextMatcher(banned, review, exceptions).match(title, description);
    }
}
