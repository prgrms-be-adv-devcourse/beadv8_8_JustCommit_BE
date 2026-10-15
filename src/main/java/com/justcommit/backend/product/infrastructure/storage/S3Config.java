package com.justcommit.backend.product.infrastructure.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

// S3 설정. 운영은 EC2 IAM 역할, 로컬은 RustFS(endpoint 지정 시)
@Configuration
public class S3Config {

    private final Region region;
    private final String endpoint;
    private final String accessKey;
    private final String secretKey;

    public S3Config(@Value("${custom.s3.region}") String region,
                    @Value("${custom.s3.endpoint:}") String endpoint,
                    @Value("${custom.s3.access-key:}") String accessKey,
                    @Value("${custom.s3.secret-key:}") String secretKey) {
        this.region = Region.of(region);
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    // 업로드 링크 발급용 (서명만 계산, S3에 요청하지 않음)
    @Bean
    public S3Presigner s3Presigner() {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentialsProvider());

        if (isLocal()) {
            // 로컬 RustFS: 주소 지정, path-style(localhost:9000/버킷)
            builder.endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    // 이미지 삭제용 (S3에 실제로 요청)
    @Bean
    public S3Client s3Client() {
        S3ClientBuilder builder = S3Client.builder()
                .region(region)
                .credentialsProvider(credentialsProvider());

        if (isLocal()) {
            builder.endpointOverride(URI.create(endpoint))
                    .forcePathStyle(true);
        }
        return builder.build();
    }

    // endpoint가 있으면 로컬, 없으면 운영
    private boolean isLocal() {
        return StringUtils.hasText(endpoint);
    }

    // 로컬은 RustFS 더미 계정, 운영은 키 없이 EC2 IAM 역할을 자동으로 사용
    private AwsCredentialsProvider credentialsProvider() {
        if (isLocal()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}