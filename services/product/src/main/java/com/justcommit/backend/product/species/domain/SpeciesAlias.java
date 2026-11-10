package com.justcommit.backend.product.species.domain;

import com.justcommit.backend.product.species.domain.enums.AliasSource;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 품종의 별칭(유통명). 같은 별칭이 여러 품종에 걸릴 수 있어 alias만으로는 unique가 아니다
@Entity
@Table(name = "species_alias",
        uniqueConstraints = @UniqueConstraint(columnNames = {"species_id", "alias"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpeciesAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "species_id", nullable = false)
    private Species species;

    @Column(nullable = false)
    private String alias;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AliasSource source;
}
