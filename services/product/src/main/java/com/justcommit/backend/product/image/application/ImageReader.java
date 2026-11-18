package com.justcommit.backend.product.image.application;

// 상품 사진 URL로 저장소(S3 · RustFS)에서 사진을 읽음
// 외부 API에 URL을 넘기지 않는 이유: 로컬 RustFS 주소(localhost)는 외부에서 열 수 없고, 버킷을 공개하지 않아도 되게
public interface ImageReader {

    // 우리 버킷의 상품 사진이 아니거나 읽지 못하면 예외
    ImageData read(String imageUrl);
}
