package com.justcommit.backend.product.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.domain.exception.ProductErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProductImageUrlValidator {

    private final ProductImageUrls imageUrls;

    public ProductImageUrlValidator(ProductImageUrls imageUrls) {
        this.imageUrls = imageUrls;
    }

    public void validate(Long sellerId, List<String> pictureUrls) {
        for (String url : pictureUrls) {
            if (!imageUrls.isOwnedBy(sellerId, url)) {
                throw new BusinessException(ProductErrorCode.INVALID_IMAGE_URL);
            }
        }
    }
}
