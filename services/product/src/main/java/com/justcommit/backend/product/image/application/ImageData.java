package com.justcommit.backend.product.image.application;

// 저장소에서 읽은 사진 (외부 API에 base64로 보냄)
public record ImageData(byte[] bytes, String mediaType) {
}
