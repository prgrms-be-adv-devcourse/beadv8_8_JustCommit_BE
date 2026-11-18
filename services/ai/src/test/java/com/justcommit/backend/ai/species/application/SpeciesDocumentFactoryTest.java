package com.justcommit.backend.ai.species.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.justcommit.backend.ai.species.domain.SpeciesProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 문서 해시에 임베딩 모델 · 차원(custom.embedding.*)이 들어가, 설정만 바꿔도 재임베딩 대상이 된다 (#508)
class SpeciesDocumentFactoryTest {

    private static final SpeciesProfile MONSTERA = new SpeciesProfile(
            62, "12345", "몬스테라", "Monstera deliciosa", "Monstera", "Monstera", null, "천남성과",
            null, null, "EASY", null, null, null, null, null, null, null, null, null,
            null, null, null, null, "잎이 크게 갈라진다", null, null, null, null);

    @Test
    @DisplayName("같은 모델 · 차원이면 해시가 같다")
    void sameModel() {
        String a = new SpeciesDocumentFactory("text-embedding-3-small", 1536).create(MONSTERA).hash();
        String b = new SpeciesDocumentFactory("text-embedding-3-small", 1536).create(MONSTERA).hash();

        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("모델이나 차원 설정을 바꾸면 문서가 같아도 해시가 바뀐다")
    void differentModelOrDimensions() {
        String base = new SpeciesDocumentFactory("text-embedding-3-small", 1536).create(MONSTERA).hash();

        assertThat(new SpeciesDocumentFactory("multilingual-e5-small", 1536).create(MONSTERA).hash())
                .isNotEqualTo(base);
        assertThat(new SpeciesDocumentFactory("text-embedding-3-small", 768).create(MONSTERA).hash())
                .isNotEqualTo(base);
    }
}
