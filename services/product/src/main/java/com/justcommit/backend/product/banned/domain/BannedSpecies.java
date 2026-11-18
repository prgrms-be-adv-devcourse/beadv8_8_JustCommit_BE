package com.justcommit.backend.product.banned.domain;

import com.justcommit.backend.product.banned.domain.enums.BannedBasis;
import com.justcommit.backend.product.banned.domain.enums.TaxonRank;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.util.ArrayList;
import java.util.List;

// 판매 금지 품종 (품종 목록 species와 따로, 법령 학명 기준). 데이터는 Flyway V8로만 바뀜
@Entity
@Immutable
@Table(name = "banned_species")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BannedSpecies {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scientific_name", nullable = false, length = 100)
    private String scientificName;

    @Enumerated(EnumType.STRING)
    @Column(name = "taxon_rank", nullable = false, length = 10)
    private TaxonRank taxonRank;

    @Column(name = "korean_name", nullable = false, length = 50)
    private String koreanName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BannedBasis basis;

    @Column(nullable = false)
    private String source;

    @Column(length = 500)
    private String memo;

    @OneToMany(mappedBy = "bannedSpecies")
    private List<BannedSpeciesAlias> aliases = new ArrayList<>();
}
