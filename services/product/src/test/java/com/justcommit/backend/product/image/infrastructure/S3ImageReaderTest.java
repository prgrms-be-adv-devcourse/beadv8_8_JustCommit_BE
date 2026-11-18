package com.justcommit.backend.product.image.infrastructure;

import com.justcommit.backend.product.image.application.ImageData;
import com.justcommit.backend.product.image.application.ProductImageUrls;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class S3ImageReaderTest {

    private final S3Client s3Client = mock(S3Client.class);
    private final S3ImageReader reader = new S3ImageReader(
            s3Client, new ProductImageUrls("http://localhost:9000/bucket"), "bucket");

    @Test
    @DisplayName("URL에서 키를 뽑아 읽고 Content-Type을 그대로 씀")
    @SuppressWarnings("unchecked")
    void read() {
        given(s3Client.getObjectAsBytes(any(Consumer.class))).willReturn(ResponseBytes.fromByteArray(
                GetObjectResponse.builder().contentType("image/png").build(), new byte[]{7}));

        ImageData image = reader.read("http://localhost:9000/bucket/products/1/a.png");

        assertThat(image.bytes()).containsExactly(7);
        assertThat(image.mediaType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("Content-Type이 없으면 확장자로")
    @SuppressWarnings("unchecked")
    void mediaTypeFromExtension() {
        given(s3Client.getObjectAsBytes(any(Consumer.class))).willReturn(ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(), new byte[]{7}));

        assertThat(reader.read("http://localhost:9000/bucket/products/1/a.jpg").mediaType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("우리 버킷의 상품 사진이 아니면 읽지 않음")
    void otherUrl() {
        assertThatThrownBy(() -> reader.read("https://evil.example.com/products/1/a.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(s3Client);
    }
}
