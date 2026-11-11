package com.justcommit.backend.product.product.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.species.domain.Species;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.util.*;

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

    // 판매자가 수정 · 삭제할 수 있는 상태 (정책 「수정」 · 「삭제」)
    private static final Set<ProductStatus> CHANGEABLE_STATUSES =
            EnumSet.of(ProductStatus.ACTIVE, ProductStatus.REJECTED, ProductStatus.REVIEW_PENDING);

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

    @Column(name = "review_version", nullable = false)
    private int reviewVersion;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    @BatchSize(size = 100)
    private List<ProductImage> images = new ArrayList<>();

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
        this.status = ProductStatus.REVIEW_PENDING;   // 등록하면 검수부터
        this.hidden = false;
    }

    public static Product create(Species species, Long sellerId, String title, String description,
                                 BigDecimal price, Integer heightCm, String potSize, String growNote,
                                 List<String> imageUrls) {
        Product product = new Product(species, sellerId, title, description, price, heightCm, potSize, growNote);
        product.replaceImages(imageUrls);
        return product;
    }

    // 사진 목록을 통째로 교체 (등록, 수정 공통). 리스트 순서가 sort_order가 됨
    public void replaceImages(List<String> imageUrls) {
        this.images.clear();
        for (int i = 0; i < imageUrls.size(); i++) {
            this.images.add(new ProductImage(this, imageUrls.get(i), i));
        }
    }

    public String getThumbnailUrl() {
        return images.isEmpty() ? null : images.get(0).getS3Url();
    }

    public boolean isOwnedBy(Long memberId) {
        return sellerId.equals(memberId);
    }

    public boolean isPurchasable() {
        return status == ProductStatus.ACTIVE && !hidden;
    }

    // 반환값: 다시 할 검수 범위 (#345에서 검수 요청 이벤트에 싣는다)
    public ReviewScope update(Species species, String title, String description,
                              BigDecimal price, Integer heightCm, String potSize, String growNote,
                              List<String> imageUrls) {
        if (!CHANGEABLE_STATUSES.contains(status)) {
            throw new BusinessException(ProductErrorCode.PRODUCT_NOT_EDITABLE);
        }

        // 순서만 바뀐 사진은 같은 사진으로 본다. 품종이 바뀌면 품종 대조가 필요해 사진 검수부터
        boolean imagesChanged = !new HashSet<>(currentImageUrls()).equals(new HashSet<>(imageUrls));
        boolean speciesChanged = !Objects.equals(this.species.getId(), species.getId());
        ReviewScope scope = (imagesChanged || speciesChanged) ? ReviewScope.FULL : ReviewScope.TEXT_ONLY;

        this.species = species;
        this.title = title;
        this.description = description;
        this.price = price;
        this.heightCm = heightCm;
        this.potSize = potSize;
        this.growNote = growNote;
        replaceImages(imageUrls);

        // 판매 중 상품의 글만 고치면 판매를 멈추지 않고 글 검사만 한다. 걸리면 그때 REJECTED
        if (!(status == ProductStatus.ACTIVE && scope == ReviewScope.TEXT_ONLY)) {
            this.status = ProductStatus.REVIEW_PENDING;
        }
        this.reviewVersion++;
        return scope;
    }

    private List<String> currentImageUrls() {
        return images.stream().map(ProductImage::getS3Url).toList();
    }

    public void delete() {
        if (!CHANGEABLE_STATUSES.contains(status)) {
            throw new BusinessException(ProductErrorCode.PRODUCT_NOT_DELETABLE);
        }
        this.status = ProductStatus.DELETED;
    }

    // 검수 중 · 반려
    public boolean isInReview() {
        return status == ProductStatus.REVIEW_PENDING || status == ProductStatus.REJECTED;
    }

    // 삭제된 상품은 아무에게도 안 보임. 숨김 · 검수 중 · 반려 상품은 판매자 본인에게만 보임
    public boolean isVisibleTo(Long memberId) {
        if (status == ProductStatus.DELETED) {
            return false;
        }
        if (isOwnedBy(memberId)) {
            return true;
        }
        return !hidden && !isInReview();
    }

    public void changeHidden(boolean hidden){
        if (status == ProductStatus.DELETED) {
            throw new BusinessException(ProductErrorCode.PRODUCT_NOT_HIDEABLE);
        }
        this.hidden = hidden;
    }

}