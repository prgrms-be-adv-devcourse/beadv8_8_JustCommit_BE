package com.justcommit.backend.product.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.domain.exception.ProductErrorCode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "product",
        indexes = {
                @Index(name = "idx_product_species", columnList = "species_id"),
                @Index(name = "idx_product_seller", columnList = "seller_id"),
                @Index(name = "idx_product_status", columnList = "status"),
                @Index(name = "idx_product_reserved_order", columnList = "reserved_order_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseTimeEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "species_id", nullable = false)
    private Species species;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 19)
    private BigDecimal price;

    private Integer heightCm;

    @Column(length = 20)
    private String potSize;

    @Column(length = 500)
    private String growNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    @Column(name = "is_hidden", nullable = false)
    private boolean hidden;

    @Column(name = "reserved_order_id")
    private Long reservedOrderId;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    @BatchSize(size = 100)
    private List<ProductPicture> pictures = new ArrayList<>();

    private Product(Species species, Long sellerId, String title, String description,
                    BigDecimal price, Integer heightCm, String potSize, String growNote) {
        this.species = species;
        this.sellerId = sellerId;
        this.title = title;
        this.description = description;
        this.price = price;
        this.heightCm = heightCm;
        this.potSize = potSize;
        this.growNote = growNote;
        this.status = ProductStatus.ACTIVE;
        this.hidden = false;
    }

    public static Product create(Species species, Long sellerId, String title, String description,
                                 BigDecimal price, Integer heightCm, String potSize, String growNote,
                                 List<String> pictureUrls) {
        if (species.isBanned()) {
            throw new BusinessException(ProductErrorCode.BANNED_SPECIES);
        }
        Product product = new Product(species, sellerId, title, description, price, heightCm, potSize, growNote);
        product.replacePictures(pictureUrls);
        return product;
    }

    // 사진 목록을 통째로 교체 (등록, 수정 공통). 리스트 순서가 sort_order가 됨
    public void replacePictures(List<String> pictureUrls) {
        this.pictures.clear();
        for (int i = 0; i < pictureUrls.size(); i++) {
            this.pictures.add(new ProductPicture(this, pictureUrls.get(i), i));
        }
    }

    public String getThumbnailUrl() {
        return pictures.isEmpty() ? null : pictures.get(0).getS3Url();
    }

    public boolean isOwnedBy(Long memberId) {
        return sellerId.equals(memberId);
    }

    public boolean isPurchasable() {
        return status == ProductStatus.ACTIVE && !hidden;
    }
}