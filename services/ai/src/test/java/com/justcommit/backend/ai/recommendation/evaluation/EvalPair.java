package com.justcommit.backend.ai.recommendation.evaluation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 추천 평가 세트 한 줄 (#507): 기준 품종을 장바구니에 담았을 때 Top-10에 들어와야 하는 짝과 근거.
 * 원본은 test resources의 {@code recommendation/eval-pairs.csv}
 */
record EvalPair(int baseId, String baseName, int answerId, String answerName, String reason) {

    static List<EvalPair> load() {
        try (InputStream in = EvalPair.class.getResourceAsStream("/recommendation/eval-pairs.csv")) {
            if (in == null) {
                throw new IllegalStateException("recommendation/eval-pairs.csv가 없습니다");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .skip(1)
                    .filter(line -> !line.isBlank())
                    .map(line -> line.split(",", 5))
                    .map(c -> new EvalPair(Integer.parseInt(c[0]), c[1], Integer.parseInt(c[2]), c[3], c[4]))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
