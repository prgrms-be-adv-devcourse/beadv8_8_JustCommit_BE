package com.justcommit.backend.product.review.infrastructure;

import com.anthropic.models.messages.OutputConfig;
import com.justcommit.backend.product.image.application.ImageData;
import com.justcommit.backend.product.review.application.ReviewEngineException;
import com.justcommit.backend.product.review.domain.PlantIdentification;
import com.justcommit.backend.product.review.domain.enums.SaleForm;
import com.justcommit.backend.product.review.infrastructure.HaikuPlantIdentifier.Answer;
import com.justcommit.backend.product.review.infrastructure.HaikuPlantIdentifier.AnswerCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 API는 부르지 않음 (요청 모양 · 응답 변환 · 키 없음만)
class HaikuPlantIdentifierTest {

    private final List<ImageData> images = List.of(new ImageData(new byte[]{1}, "image/jpeg"), new ImageData(new byte[]{2}, "image/png"));

    @Test
    @DisplayName("요청: Haiku 모델, 사진 전부 + 안내 글, 시스템 프롬프트에 금지 품종 목록")
    void request() {
        HaikuPlantIdentifier identifier = new HaikuPlantIdentifier("test-key");
        var params = identifier.request(images, List.of("나도풍란(Sedirea japonica)", "양귀비(Papaver somniferum)")).rawParams();

        assertThat(params.model().asString()).isEqualTo("claude-haiku-5-5");
        assertThat(params.messages()).hasSize(1);
        assertThat(params.messages().get(0).content().asContentBlockParams())
                .hasSize(3)
                .satisfies(blocks -> {
                    assertThat(blocks.get(0).isImage()).isTrue();
                    assertThat(blocks.get(1).isImage()).isTrue();
                    assertThat(blocks.get(2).isText()).isTrue();
                });
        assertThat(params.system().orElseThrow().asString()).contains("나도풍란(Sedirea japonica)", "양귀비(Papaver somniferum)");
        assertThat(params.outputConfig()).isPresent();
        // 생각 토큰도 max_tokens에 들어가므로 한도는 넉넉히, 생각 깊이는 LOW (1024면 JSON이 잘렸음)
        assertThat(params.maxTokens()).isEqualTo(HaikuPlantIdentifier.MAX_TOKENS).isGreaterThanOrEqualTo(8000);
        assertThat(params.outputConfig().orElseThrow().effort()).contains(OutputConfig.Effort.LOW);
    }

    @Test
    @DisplayName("키가 없으면 부르지 않고 ReviewEngineException (관리자 확인으로)")
    void noKey() {
        HaikuPlantIdentifier identifier = new HaikuPlantIdentifier("");
        assertThat(identifier.isConfigured()).isFalse();
        assertThat(new HaikuPlantIdentifier("test-key").isConfigured()).isTrue();
        assertThatThrownBy(() -> identifier.identify(images, List.of())).isInstanceOf(ReviewEngineException.class);
    }

    @Test
    @DisplayName("응답 변환: 0~1 밖 확신도는 버리고 3개까지, 확신도 높은 순")
    void toDomain() {
        Answer answer = new Answer(true, SaleForm.PLANT, List.of(
                new AnswerCandidate("Ficus elastica", "고무나무", 0.2),
                new AnswerCandidate("Monstera deliciosa", "몬스테라", 0.8),
                new AnswerCandidate("Broken name", null, 1.5),
                new AnswerCandidate("Monstera adansonii", "아단소니", 0.5),
                new AnswerCandidate("Philodendron", null, 0.1)));

        PlantIdentification result = HaikuPlantIdentifier.toDomain(answer);

        assertThat(result.plant()).isTrue();
        assertThat(result.saleForm()).isEqualTo(SaleForm.PLANT);
        assertThat(result.candidates()).extracting(c -> c.scientificName())
                .containsExactly("Monstera deliciosa", "Monstera adansonii", "Ficus elastica");
    }

    @Test
    @DisplayName("식물인데 판매 형태가 없으면 해석 불가")
    void missingSaleForm() {
        assertThatThrownBy(() -> HaikuPlantIdentifier.toDomain(new Answer(true, null, List.of())))
                .isInstanceOf(ReviewEngineException.class);
    }
}
