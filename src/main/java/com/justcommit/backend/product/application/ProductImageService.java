package com.justcommit.backend.product.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.MemberQuery;
import com.justcommit.backend.product.domain.exception.ProductErrorCode;
import com.justcommit.backend.product.presentation.dto.ImageUploadUrlRequest;
import com.justcommit.backend.product.presentation.dto.ImageUploadUrlResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductImageService {

    // 허용 형식과 저장할 확장자
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");
    private static final long MAX_SIZE = 10L * 1024 * 1024;   // 10MB

    private final ImageUploadUrlIssuer uploadUrlIssuer;
    private final MemberQuery memberQuery;
    private final ProductImageUrls imageUrls;

    public ProductImageService(ImageUploadUrlIssuer uploadUrlIssuer,
                               MemberQuery memberQuery,
                               ProductImageUrls imageUrls) {
        this.uploadUrlIssuer = uploadUrlIssuer;
        this.memberQuery = memberQuery;
        this.imageUrls = imageUrls;
    }

    public List<ImageUploadUrlResponse> issueUploadUrls(Long sellerId, ImageUploadUrlRequest request) {
        if (!memberQuery.isSeller(sellerId)) {
            throw new BusinessException(ProductErrorCode.NOT_SELLER);
        }
        return request.files().stream()
                .map(file -> issue(sellerId, file.contentType(), file.size()))
                .toList();
    }

    private ImageUploadUrlResponse issue(Long sellerId, String contentType, long size) {
        String extension = EXTENSIONS.get(contentType);
        if (extension == null || size > MAX_SIZE) {
            throw new BusinessException(ProductErrorCode.INVALID_IMAGE_FILE);
        }
        String key = imageUrls.keyOf(sellerId, UUID.randomUUID() + "." + extension);

        return new ImageUploadUrlResponse(
                uploadUrlIssuer.issue(key, contentType, size),
                imageUrls.urlOf(key));
    }
}