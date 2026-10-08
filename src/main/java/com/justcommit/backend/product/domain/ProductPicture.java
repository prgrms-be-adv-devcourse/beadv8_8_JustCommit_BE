package com.justcommit.backend.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Entity
@Table(name = "product_picture")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductPicture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "s3_url", nullable = false)
    private String s3Url;

    @Column(nullable = false)
    private int sortOrder;

    ProductPicture(Product product, String s3Url, int sortOrder) {
        this.product = product;
        this.s3Url = s3Url;
        this.sortOrder = sortOrder;
    }
}