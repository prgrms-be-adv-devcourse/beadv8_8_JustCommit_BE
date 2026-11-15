package com.justcommit.backend.product.review.application;

import com.justcommit.backend.product.image.application.ImageData;

// 유해 검사 (OpenAI Moderation). 실패하면 ReviewEngineException
public interface ModerationClient {

    boolean isImageFlagged(ImageData image);

    boolean isTextFlagged(String text);

    // API 키가 있는지 (OPENAI_API_KEY). 없으면 재처리해도 소용없어 엔진 오류를 바로 관리자 확인으로
    boolean isConfigured();
}
