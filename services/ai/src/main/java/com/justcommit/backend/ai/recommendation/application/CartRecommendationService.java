package com.justcommit.backend.ai.recommendation.application;

import com.justcommit.backend.ai.recommendation.application.RecommendationScorer.ScoredSpecies;
import com.justcommit.backend.ai.recommendation.infrastructure.RecommendationQueryRepository;
import com.justcommit.backend.ai.recommendation.infrastructure.RecommendationQueryRepository.ActiveProduct;
import com.justcommit.backend.ai.recommendation.infrastructure.RecommendationQueryRepository.Candidate;
import com.justcommit.backend.ai.species.domain.SpeciesProfile;
import com.justcommit.backend.ai.species.infrastructure.SpeciesProfileRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 장바구니 추천 (#294, docs/flows/search-recommend.md 「추천」).
 * 상품 ID → 품종 → 품종 벡터 평균으로 후보 30개 → 점수 · 같은 속 분산 → 품종을 돌아가며 판매 중 상품 → 이유 문장
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartRecommendationService {

    static final int CANDIDATE_LIMIT = 30;
    // 「기타」 품종: 어떤 식물인지 몰라 기준으로 쓰지 않는다 (벡터도 없음)
    static final int OTHER_SPECIES_ID = 218;

    private final RecommendationQueryRepository queryRepository;
    private final SpeciesProfileRepository speciesProfileRepository;

    public List<Recommendation> recommend(List<Long> cartProductIds, List<Long> purchasedProductIds, int size) {
        long start = System.nanoTime();
        Set<Long> cart = new LinkedHashSet<>(cartProductIds);
        Set<Long> sourceProducts = new LinkedHashSet<>(cart);
        sourceProducts.addAll(purchasedProductIds);

        // 1. 상품 → 기준 품종 (모든 상태, 품종은 한 번만). 「기타」와 벡터가 없는 품종은 벡터 평균 · 환경 점수 ·
        //    난이도 감점 · 이유 문장 모두에서 빼야 해서 여기서 거른다
        Set<Integer> baseSpeciesIds = new LinkedHashSet<>(queryRepository.findSpeciesIds(sourceProducts).values());
        baseSpeciesIds.remove(OTHER_SPECIES_ID);
        baseSpeciesIds.retainAll(queryRepository.findSpeciesIdsWithVector(baseSpeciesIds));
        if (baseSpeciesIds.isEmpty()) {
            return List.of();
        }

        // 2. 기준 품종 벡터 평균과 가까운 품종 (판매 중 상품이 있는 품종 안에서)
        List<Candidate> candidates = queryRepository.findCandidates(baseSpeciesIds, cart, CANDIDATE_LIMIT);
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 3. 점수 · 같은 속 분산
        Set<Integer> profileIds = new LinkedHashSet<>(baseSpeciesIds);
        candidates.forEach(c -> profileIds.add(c.speciesId()));
        Map<Integer, SpeciesProfile> profiles = speciesProfileRepository.findAllById(profileIds).stream()
                .collect(Collectors.toMap(SpeciesProfile::speciesId, Function.identity()));
        List<SpeciesProfile> bases = baseSpeciesIds.stream().map(profiles::get).filter(p -> p != null).toList();
        List<ScoredSpecies> ordered = RecommendationScorer.diversifyByGenus(
                RecommendationScorer.score(candidates, profiles, bases));

        // 4. 품종을 점수 순으로 돌아가며 상품을 하나씩
        List<Recommendation> result = pickProducts(ordered, cart, size);
        log.debug("장바구니 추천: 기준 품종 {}개, 후보 {}개, 결과 {}개, {}ms", baseSpeciesIds.size(), candidates.size(),
                result.size(), (System.nanoTime() - start) / 1_000_000);
        return result;
    }

    private List<Recommendation> pickProducts(List<ScoredSpecies> ordered, Set<Long> cart, int size) {
        List<Integer> speciesIds = ordered.stream().map(s -> s.species().speciesId()).toList();
        Map<Integer, Deque<Long>> productsBySpecies = new HashMap<>();
        for (ActiveProduct product : queryRepository.findActiveProducts(speciesIds, cart, size)) {
            productsBySpecies.computeIfAbsent(product.speciesId(), id -> new ArrayDeque<>()).add(product.productId());
        }

        Map<Integer, String> reasons = new LinkedHashMap<>();
        for (ScoredSpecies s : ordered) {
            if (s.bestBase() != null) {
                reasons.put(s.species().speciesId(), RecommendationReason.of(s.bestBase(), s.bestMatch()));
            }
        }

        List<Recommendation> result = new ArrayList<>();
        boolean added = true;
        while (result.size() < size && added) {
            added = false;
            for (Integer speciesId : speciesIds) {
                Deque<Long> products = productsBySpecies.get(speciesId);
                if (products == null || products.isEmpty() || !reasons.containsKey(speciesId)) {
                    continue;
                }
                result.add(new Recommendation(products.poll(), reasons.get(speciesId)));
                added = true;
                if (result.size() == size) {
                    break;
                }
            }
        }
        return result;
    }
}
