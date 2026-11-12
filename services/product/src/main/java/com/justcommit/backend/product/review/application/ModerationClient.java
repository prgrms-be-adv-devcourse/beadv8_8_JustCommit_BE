package com.justcommit.backend.product.review.application;

import com.justcommit.backend.product.image.application.ImageData;

// 유해 검사 (OpenAI Moderation). 실패하면 ReviewEngineException
public interface ModerationClient {

    boolean isImageFlagged(ImageData image);

    boolean isTextFlagged(String text);
}
