package com.justcommit.backend.product.infrastructure.storage;

import com.justcommit.backend.product.application.ImageDeleter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;

@Slf4j
@Component
public class S3ImageDeleter implements ImageDeleter {

    private final S3Client s3Client;
    private final String bucket;

    public S3ImageDeleter(S3Client s3Client, @Value("${custom.s3.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    @Override
    public void delete(List<String> keys) {
        for (String key : keys) {
            try {
                s3Client.deleteObject(request -> request.bucket(bucket).key(key));
            } catch (RuntimeException exception) {
                // 상품 변경은 이미 커밋됨. 파일이 남을 뿐이라 실패해도 되돌리지 않음
                log.warn("상품 이미지 삭제 실패: key={}", key, exception);
            }
        }
    }
}