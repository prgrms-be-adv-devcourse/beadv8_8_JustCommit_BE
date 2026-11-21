package com.justcommit.backend.product.image.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.client.member.MemberQuery;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.image.presentation.dto.ImageUploadUrlRequest;
import com.justcommit.backend.product.image.presentation.dto.ImageUploadUrlResponse;
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
    // 7.5MB: 검수 때 Haiku에 base64(원본의 4/3)로 보내므로 Anthropic 이미지 한도 10MB 안에 들게 (#594)
    static final long MAX_SIZE = 7_864_320L;

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