package com.justcommit.backend.product.image.infrastructure;

import com.justcommit.backend.product.image.application.ImageData;
import com.justcommit.backend.product.image.application.ImageReader;
import com.justcommit.backend.product.image.application.ProductImageUrls;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

@Component
public class S3ImageReader implements ImageReader {

    private final S3Client s3Client;
    private final ProductImageUrls imageUrls;
    private final String bucket;

    public S3ImageReader(S3Client s3Client, ProductImageUrls imageUrls, @Value("${custom.s3.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.imageUrls = imageUrls;
        this.bucket = bucket;
    }

    @Override
    public ImageData read(String imageUrl) {
        String key = imageUrls.keyFromUrl(imageUrl);
        if (key == null) {
            throw new IllegalArgumentException("우리 버킷의 상품 사진이 아닙니다: " + imageUrl);
        }
        ResponseBytes<GetObjectResponse> object = s3Client.getObjectAsBytes(request -> request.bucket(bucket).key(key));
        return new ImageData(object.asByteArray(), mediaType(object.response().contentType(), key));
    }

    // 업로드 때 JPEG · PNG만 받음. 저장된 Content-Type이 없으면 확장자로
    private static String mediaType(String contentType, String key) {
        if (contentType != null && contentType.startsWith("image/")) {
            return contentType;
        }
        return key.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
    }
}
