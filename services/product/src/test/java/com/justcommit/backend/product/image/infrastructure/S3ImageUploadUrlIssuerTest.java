package com.justcommit.backend.product.image.infrastructure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class S3ImageUploadUrlIssuerTest {

    // presigend URL 생성
    private final S3Presigner presigner = S3Presigner.builder()
            .region(Region.AP_NORTHEAST_2)
            .endpointOverride(URI.create("http://localhost:9000"))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .build();

    private final S3ImageUploadUrlIssuer issuer =
            new S3ImageUploadUrlIssuer(presigner, "test-bucket", Duration.ofMinutes(10));

    @AfterEach
    void close() {
        presigner.close();
    }

    @Test
    @DisplayName("버킷·키 위치로 10분짜리 업로드 링크를 만들고, 크기와 형식을 서명에 포함")
    void issue() {
        String url = issuer.issue("products/7/abc.jpg", "image/jpeg", 2_000L);

        //검증
        assertThat(url).startsWith("http://localhost:9000/test-bucket/products/7/abc.jpg?");
        assertThat(url).contains("X-Amz-Expires=600");
        assertThat(url).contains("X-Amz-SignedHeaders=content-length%3Bcontent-type%3Bhost");
    }
}