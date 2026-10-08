package com.justcommit.backend.product.domain;


import com.justcommit.backend.common.entity.BaseTimeEntity;
import com.justcommit.backend.product.domain.enums.*;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "species")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Species extends BaseTimeEntity {

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Light light;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private Watering watering;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Difficulty difficulty;

    @Enumerated(EnumType.STRING)
    @Column(name = "color", length = 20)
    private LeafColor color;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private SpeciesCategory category;

    @Column(name = "is_banned", nullable = false)
    private boolean banned;

    @OneToMany(mappedBy = "species")
    @OrderBy("sortOrder ASC")
    @BatchSize(size = 100)
    private List<SpeciesPicture> pictures = new ArrayList<>();

    public String getThumbnailUrl() {
        return pictures.isEmpty() ? null : pictures.get(0).getS3Url();
    }
}
