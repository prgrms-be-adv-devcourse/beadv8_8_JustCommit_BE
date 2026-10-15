package com.justcommit.backend.product.infrastructure.storage;

import com.justcommit.backend.product.application.ImageUploadUrlIssuer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;

@Component
public class S3ImageUploadUrlIssuer implements ImageUploadUrlIssuer {

    private final S3Presigner s3Presigner;
    private final String bucket;
    private final Duration uploadUrlDuration;

    public S3ImageUploadUrlIssuer(S3Presigner s3Presigner,
                                  @Value("${custom.s3.bucket}") String bucket,
                                  @Value("${custom.s3.upload-url-duration}") Duration uploadUrlDuration) {
        this.s3Presigner = s3Presigner;
        this.bucket = bucket;
        this.uploadUrlDuration = uploadUrlDuration;
    }

    @Override
    public String issue(String key, String contentType, long size) {
        // contentType·size를 서명에 포함 → 다른 형식이나 크기로 올리면 S3가 403으로 거부
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(size)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(uploadUrlDuration)
                .putObjectRequest(putRequest)
                .build();

        return s3Presigner.presignPutObject(presignRequest).url().toString();
    }
}