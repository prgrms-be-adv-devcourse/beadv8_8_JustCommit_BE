package com.justcommit.backend.product.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.domain.exception.ProductErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProductImageUrlValidator {

    private final String baseUrl;

    public ProductImageUrlValidator(@Value("${custom.image.base-url}") String baseUrl){
        this.baseUrl =baseUrl;
    }

    public void validate(Long sellerId, List<String> pictureUrls){
        String allowedPrefix = baseUrl + "/products/" + sellerId + "/";
        for (String url : pictureUrls) {
            if (!url.startsWith(allowedPrefix) || url.contains("..")) {
                throw new BusinessException(ProductErrorCode.INVALID_IMAGE_URL);
            }
        }
    }
}
