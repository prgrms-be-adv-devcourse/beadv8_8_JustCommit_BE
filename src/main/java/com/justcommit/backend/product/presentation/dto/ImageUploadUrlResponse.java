package com.justcommit.backend.product.presentation.dto;

// uploadUrl: S3에 PUT할 서명 링크(10분) / imageUrl: 상품 등록 pictureUrls에 넣을 최종 주소
public record ImageUploadUrlResponse(String uploadUrl, String imageUrl) {
}