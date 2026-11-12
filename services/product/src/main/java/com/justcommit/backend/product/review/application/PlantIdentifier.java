package com.justcommit.backend.product.review.application;

import com.justcommit.backend.product.image.application.ImageData;
import com.justcommit.backend.product.review.domain.PlantIdentification;

import java.util.List;

// 식물 식별 (Claude Haiku). bannedNames: 프롬프트에 넣을 금지 품종 목록. 실패하면 ReviewEngineException
public interface PlantIdentifier {

    PlantIdentification identify(List<ImageData> images, List<String> bannedNames);
}
