package com.justcommit.backend.product.banned.domain;

import com.justcommit.backend.product.banned.domain.enums.BannedAliasType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

// 금지 품종의 학명 동의어 · 한글 별칭
@Entity
@Immutable
@Table(name = "banned_species_alias")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BannedSpeciesAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "banned_species_id", nullable = false)
    private BannedSpecies bannedSpecies;

    @Column(nullable = false, length = 100)
    private String alias;

    @Enumerated(EnumType.STRING)
    @Column(name = "alias_type", nullable = false, length = 20)
    private BannedAliasType aliasType;
}
