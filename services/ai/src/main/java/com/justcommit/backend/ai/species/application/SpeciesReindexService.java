package com.justcommit.backend.ai.species.application;

import com.justcommit.backend.ai.species.domain.SpeciesProfile;
import com.justcommit.backend.ai.species.infrastructure.SpeciesProfileRepository;
import com.justcommit.backend.ai.species.infrastructure.SpeciesVectorRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

// 품종 재임베딩: 품종 문서를 만들어 해시가 바뀐 품종만 임베딩해 species_vector_store에 upsert한다
// 같은 요청을 다시 보내도 결과가 같고, 문서가 그대로면 임베딩 모델을 부르지 않는다
@Slf4j
@Service
public class SpeciesReindexService {

    private final SpeciesProfileRepository profileRepository;
    private final SpeciesVectorRepository vectorRepository;
    private final SpeciesDocumentFactory documentFactory;
    private final PgVectorStore speciesVectorStore;

    public SpeciesReindexService(
            SpeciesProfileRepository profileRepository,
            SpeciesVectorRepository vectorRepository,
            SpeciesDocumentFactory documentFactory,
            @Qualifier("speciesVectorStore") PgVectorStore speciesVectorStore) {
        this.profileRepository = profileRepository;
        this.vectorRepository = vectorRepository;
        this.documentFactory = documentFactory;
        this.speciesVectorStore = speciesVectorStore;
    }

    public SpeciesReindexResult reindex() {
        List<SpeciesProfile> profiles = profileRepository.findAll();
        Map<Integer, String> storedHashes = vectorRepository.findDocumentHashes();

        List<Document> changed = new ArrayList<>();
        Set<Integer> profileIds = new HashSet<>();
        for (SpeciesProfile profile : profiles) {
            profileIds.add(profile.speciesId());
            SpeciesDocument document = documentFactory.create(profile);
            if (!Objects.equals(storedHashes.get(document.speciesId()), document.hash())) {
                changed.add(toVectorDocument(document));
            }
        }

        // 프로필에서 빠진 품종의 벡터는 지운다 (시드에서 품종을 빼는 경우)
        List<String> removed = storedHashes.keySet().stream()
                .filter(id -> !profileIds.contains(id))
                .map(String::valueOf)
                .toList();
        if (!removed.isEmpty()) {
            speciesVectorStore.delete(removed);
        }
        if (!changed.isEmpty()) {
            speciesVectorStore.add(changed);
        }

        SpeciesReindexResult result = new SpeciesReindexResult(
                profiles.size(), changed.size(), profiles.size() - changed.size(), removed.size());
        log.info("품종 재임베딩 완료: {}", result);
        return result;
    }

    private static Document toVectorDocument(SpeciesDocument document) {
        return Document.builder()
                .id(String.valueOf(document.speciesId()))
                .text(document.text())
                .metadata(Map.of(
                        "speciesId", document.speciesId(),
                        "name", document.name(),
                        "documentHash", document.hash()))
                .build();
    }
}
