package com.justcommit.backend.ai.chatbot.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.justcommit.backend.ai.chatbot.application.ChatSearchResult.ProductHit;
import com.justcommit.backend.ai.chatbot.infrastructure.ChatSearchRepository;
import com.justcommit.backend.ai.chatbot.infrastructure.ConversationRepository;
import com.justcommit.backend.ai.search.application.QueryEmbedder;
import com.justcommit.backend.ai.search.infrastructure.SpeciesSearchQueryRepository;
import com.justcommit.backend.ai.search.infrastructure.SpeciesSearchQueryRepository.SimilarSpecies;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

// 챗봇 상품 검색 · 품종 검색 동시 실행 (#636): 시간은 긴 쪽 수준, 실패는 차례로 하던 때와 같은 예외
class ChatbotParallelSearchTest {

    private static final long DELAY_MS = 400;
    private static final long SPECIES_TIMEOUT_MS = 1_000;

    private final ChatSearchRepository searchRepository = mock(ChatSearchRepository.class);
    private final SpeciesSearchQueryRepository speciesRepository = mock(SpeciesSearchQueryRepository.class);
    private final QueryEmbedder embedder = (query, scope) -> new float[] {1f};
    private final AnswerGenerator answerGenerator = mock(AnswerGenerator.class);

    private final ChatbotService service = new ChatbotService(mock(ConversationRepository.class), searchRepository,
            speciesRepository, embedder, answerGenerator, mock(ChatPromptFactory.class),
            mock(TransactionTemplate.class), 6, 3, 3, 0.28, 0.35, 10, Duration.ofMillis(SPECIES_TIMEOUT_MS),
            new SimpleMeterRegistry());

    private static final ProductHit PRODUCT =
            new ProductHit(1L, 218L, "기타", "그늘 몬스테라", BigDecimal.valueOf(15000), null, "그늘", 0.4);

    @Test
    @DisplayName("상품 검색과 품종 검색을 동시에 해서 두 검색 시간의 합보다 빨리 끝난다")
    void searchesInParallel() {
        given(searchRepository.findProducts(any(), anyInt(), anyDouble())).willAnswer(i -> {
            Thread.sleep(DELAY_MS);
            return List.of(PRODUCT);
        });
        given(speciesRepository.findSimilarSpecies(any(), anyDouble(), anyInt())).willAnswer(i -> {
            Thread.sleep(DELAY_MS);
            return List.of(new SimilarSpecies(93, "산세베리아", 0.5));
        });
        given(searchRepository.findSpeciesDocuments(any())).willReturn(Map.of(93, "그늘에서도 잘 자람"));

        long start = System.nanoTime();
        ChatbotService.ChatReply reply = service.send(1L, null, "어두운 방에서 키울 식물");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(Duration.ofMillis(DELAY_MS * 2 - 100));
        assertThat(reply.result().products()).containsExactly(PRODUCT);
        assertThat(reply.result().species()).extracting(ChatSearchResult.SpeciesHit::name).containsExactly("산세베리아");
    }

    @Test
    @DisplayName("상품 검색이 실패하면 그 예외를 감싸지 않고 그대로 던진다 (품종 검색이 성공해도)")
    void productFailureIsRethrown() {
        given(searchRepository.findProducts(any(), anyInt(), anyDouble()))
                .willThrow(new IllegalStateException("DB 연결 실패"));
        given(speciesRepository.findSimilarSpecies(any(), anyDouble(), anyInt())).willReturn(List.of());

        assertThatThrownBy(() -> service.send(1L, null, "어두운 방"))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessage("DB 연결 실패");
    }

    @Test
    @DisplayName("상품 검색이 실패하면 느린 품종 검색을 기다리지 않고 바로 던진다 (#636 리뷰)")
    void productFailureDoesNotWaitForSpecies() {
        given(searchRepository.findProducts(any(), anyInt(), anyDouble()))
                .willThrow(new IllegalStateException("DB 연결 실패"));
        given(speciesRepository.findSimilarSpecies(any(), anyDouble(), anyInt())).willAnswer(i -> {
            Thread.sleep(SPECIES_TIMEOUT_MS * 3);
            return List.of();
        });

        long start = System.nanoTime();
        assertThatThrownBy(() -> service.send(1L, null, "어두운 방"))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessage("DB 연결 실패");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(DELAY_MS));
    }

    @Test
    @DisplayName("품종 검색이 시간 제한을 넘으면 기다리지 않고 품종 검색 실패로 던진다 (#636 리뷰)")
    void speciesSearchTimesOut() {
        given(searchRepository.findProducts(any(), anyInt(), anyDouble())).willReturn(List.of(PRODUCT));
        given(speciesRepository.findSimilarSpecies(any(), anyDouble(), anyInt())).willAnswer(i -> {
            Thread.sleep(SPECIES_TIMEOUT_MS * 3);
            return List.of();
        });

        long start = System.nanoTime();
        assertThatThrownBy(() -> service.send(1L, null, "어두운 방"))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessageContaining("품종 검색이 1000ms 안에 끝나지 않음");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(SPECIES_TIMEOUT_MS * 2));
    }

    @Test
    @DisplayName("챗봇 질문 임베딩은 회원마다 다른 캐시 범위를 씀 (#636 리뷰)")
    void embedsWithMemberScope() {
        List<String> scopes = new java.util.concurrent.CopyOnWriteArrayList<>();
        ChatbotService scoped = new ChatbotService(mock(ConversationRepository.class), searchRepository,
                speciesRepository, (query, scope) -> {
                    scopes.add(scope);
                    return new float[] {1f};
                }, answerGenerator, mock(ChatPromptFactory.class), mock(TransactionTemplate.class),
                6, 3, 3, 0.28, 0.35, 10, Duration.ofMillis(SPECIES_TIMEOUT_MS), new SimpleMeterRegistry());
        given(searchRepository.findProducts(any(), anyInt(), anyDouble())).willReturn(List.of(PRODUCT));
        given(speciesRepository.findSimilarSpecies(any(), anyDouble(), anyInt())).willReturn(List.of());

        scoped.send(1L, null, "어두운 방");
        scoped.send(2L, null, "어두운 방");

        assertThat(scopes).containsExactly("chatbot:1", "chatbot:2");
    }

    @Test
    @DisplayName("둘 다 실패하면 차례로 하던 때처럼 상품 검색의 예외를 던진다")
    void productFailureWinsWhenBothFail() {
        given(searchRepository.findProducts(any(), anyInt(), anyDouble()))
                .willThrow(new IllegalStateException("상품 검색 실패"));
        given(speciesRepository.findSimilarSpecies(any(), anyDouble(), anyInt()))
                .willThrow(new IllegalArgumentException("품종 검색 실패"));

        assertThatThrownBy(() -> service.send(1L, null, "어두운 방"))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessage("상품 검색 실패");
    }

    @Test
    @DisplayName("품종 검색만 실패해도 그 예외를 그대로 던진다")
    void speciesFailureIsRethrown() {
        given(searchRepository.findProducts(any(), anyInt(), anyDouble())).willReturn(List.of(PRODUCT));
        given(speciesRepository.findSimilarSpecies(any(), anyDouble(), anyInt()))
                .willThrow(new IllegalArgumentException("품종 검색 실패"));

        assertThatThrownBy(() -> service.send(1L, null, "어두운 방"))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("품종 검색 실패");
    }
}
