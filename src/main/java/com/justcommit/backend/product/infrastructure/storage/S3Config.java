package com.justcommit.backend.product.infrastructure.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

// S3 업로드 링크 발급용 설정. 운영은 EC2 IAM 역할, 로컬은 RustFS(endpoint 지정 시)
@Configuration
public class S3Config {

    @Bean
    public S3Presigner s3Presigner(
            @Value("${custom.s3.region}") String region,
            @Value("${custom.s3.endpoint:}") String endpoint,
            @Value("${custom.s3.access-key:}") String accessKey,
            @Value("${custom.s3.secret-key:}") String secretKey) {

        S3Presigner.Builder builder = S3Presigner.builder().region(Region.of(region));

        // endpoint 가 있으면 로컬. 없으면 운영
        if (StringUtils.hasText(endpoint)) {
            // 로컬 RustFS: 주소 지정, path-style(localhost:9000/버킷), 더미 계정
            builder.endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            // 운영: 키 없이 EC2 IAM 역할을 자동으로 사용
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return builder.build();
    }
}