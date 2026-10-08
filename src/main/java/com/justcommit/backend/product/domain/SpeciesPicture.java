package com.justcommit.backend.product.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "species_picture")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpeciesPicture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "species_id", nullable = false)
    private Species species;

    @Column(name = "s3_url", nullable = false)
    private String s3Url;

    @Column(nullable = false)
    private int sortOrder;
}
