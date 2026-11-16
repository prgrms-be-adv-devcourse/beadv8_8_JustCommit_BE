package com.justcommit.backend.ai.species.infrastructure;

import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// species_vector_store에서 재임베딩 판단에 필요한 값만 읽는다 (쓰기는 PgVectorStore가 함)
@Repository
@RequiredArgsConstructor
public class SpeciesVectorRepository {

    private final JdbcClient jdbcClient;

    // 품종 ID → 저장할 때의 문서 해시
    public Map<Integer, String> findDocumentHashes() {
        Map<Integer, String> hashes = new HashMap<>();
        jdbcClient.sql("SELECT id, metadata->>'documentHash' AS hash FROM species_vector_store")
                .query(rs -> {
                    hashes.put(rs.getInt("id"), rs.getString("hash"));
                });
        return hashes;
    }
}
