package com.justcommit.backend.product.review.application;

import com.justcommit.backend.product.image.application.ImageData;
import com.justcommit.backend.product.review.domain.PlantIdentification;

import java.util.List;

// 식물 식별 (Claude Haiku). bannedNames: 프롬프트에 넣을 금지 품종 목록. 실패하면 ReviewEngineException
public interface PlantIdentifier {

    PlantIdentification identify(List<ImageData> images, List<String> bannedNames);

    // API 키가 있는지 (ANTHROPIC_API_KEY). 없으면 재처리해도 소용없어 엔진 오류를 바로 관리자 확인으로
    boolean isConfigured();
}
