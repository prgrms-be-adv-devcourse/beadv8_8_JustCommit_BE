package com.justcommit.backend.product.domain;


import com.justcommit.backend.product.domain.enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "species")
@Getter
public class Species {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
    private List<SpeciesPicture> pictures = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
