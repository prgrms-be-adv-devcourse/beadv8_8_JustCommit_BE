package com.justcommit.backend.product.banned.domain;

import com.justcommit.backend.product.banned.domain.enums.BannedKeywordType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

// 품종이 아닌 금지어 · 관리자 확인 단어 · 예외 단어
@Entity
@Immutable
@Table(name = "banned_keyword")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BannedKeyword {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String keyword;

    @Enumerated(EnumType.STRING)
    @Column(name = "keyword_type", nullable = false, length = 20)
    private BannedKeywordType keywordType;

    @Column(length = 500)
    private String reason;
}
