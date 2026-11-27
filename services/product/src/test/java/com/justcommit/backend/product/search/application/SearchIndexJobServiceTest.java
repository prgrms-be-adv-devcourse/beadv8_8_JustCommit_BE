package com.justcommit.backend.product.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.search.application.ProductIndexService.RebuildResult;
import com.justcommit.backend.product.search.application.ProductIndexService.ReindexResult;
import com.justcommit.backend.product.search.domain.SearchIndexJobPhase;
import com.justcommit.backend.product.search.domain.SearchIndexJobType;
import com.justcommit.backend.product.search.infrastructure.SearchIndexJobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 색인 작업 기록 · 서버 간 잠금 · 죽은 작업 정리 (#563). 실제 Postgres(V15 유니크 인덱스)로 돌리고,
 * 서버 두 대는 같은 DB를 쓰는 {@link SearchIndexJobService} 두 개(worker A · B)로 흉내 낸다.
 * 재색인 본체(ProductIndexService) · OpenSearch는 목이고, 실제 OpenSearch로 도는 흐름은 ProductSearchIntegrationTest.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SearchIndexJobServiceTest {

    private static final Duration STALE_AFTER = Duration.ofMinutes(2);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @Autowired SearchIndexJobRepository repository;
    @Autowired ProductRepository productRepository;

    MutableClock clock;
    ProductIndexService indexService;
    ProductSearchIndex searchIndex;
    ApplicationEventPublisher events;
    SearchIndexJobTracker tracker;

    @BeforeEach
    void setUp() {
        repository.deleteAllInBatch();
        clock = new MutableClock(Instant.parse("2026-11-27T01:00:00Z"));
        indexService = mock(ProductIndexService.class);
        searchIndex = mock(ProductSearchIndex.class);
        events = mock(ApplicationEventPublisher.class);
        tracker = new SearchIndexJobTracker(repository, events, clock);
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAllInBatch();
    }

    @Test
    @DisplayName("[#563 완료 기준 1] 재색인 요청은 QUEUED 작업을 바로 돌려주고, 실행이 끝나면 단계 · 처리 수 · 시각이 남고 끝 이벤트가 한 번 나감")
    void rebuildIsRecorded() {
        given(indexService.rebuild(any())).willAnswer(invocation -> {
            IndexProgress progress = invocation.getArgument(0);
            progress.phase(SearchIndexJobPhase.FILLING, "products_v1", "products_v2");
            progress.batch(500, 0, 0);
            progress.batch(20, 0, 0);
            progress.phase(SearchIndexJobPhase.SWITCHING_ALIAS, "products_v1", "products_v2");
            progress.phase(SearchIndexJobPhase.CATCHING_UP, "products_v1", "products_v2");
            progress.batch(500, 499, 0);
            progress.batch(20, 20, 0);
            progress.phase(SearchIndexJobPhase.CLEANING_UP, "products_v1", "products_v2");
            return new RebuildResult("products_v1", "products_v2", new ReindexResult(520, 0, 0, 0),
                    new ReindexResult(520, 0, 519, 0), true);
        });
        // 실행자에 넘기기만 하고 아직 돌리지 않음 → 응답 시점의 상태 확인
        List<Runnable> submitted = new ArrayList<>();
        SearchIndexJobService service = service("A", submitted::add);

        SearchIndexJobStatus accepted = service.requestRebuild();

        assertThat(accepted.phase()).isEqualTo(SearchIndexJobPhase.QUEUED);
        assertThat(accepted.type()).isEqualTo(SearchIndexJobType.REBUILD);
        assertThat(service.status(accepted.jobId()).phase()).isEqualTo(SearchIndexJobPhase.QUEUED);

        submitted.getFirst().run();

        SearchIndexJobStatus done = service.status(accepted.jobId());
        assertThat(done.phase()).isEqualTo(SearchIndexJobPhase.COMPLETED);
        assertThat(done.worker()).isEqualTo("A");
        assertThat(done.previousIndex()).isEqualTo("products_v1");
        assertThat(done.nextIndex()).isEqualTo("products_v2");
        assertThat(done.processed()).isEqualTo(520);
        assertThat(done.caughtUp()).isEqualTo(new SearchIndexJobStatus.Counts(520, 519, 0));
        assertThat(done.previousDeleted()).isTrue();
        assertThat(done.totalCount()).isEqualTo(productRepository.count());
        assertThat(done.startedAt()).isNotNull();
        assertThat(done.finishedAt()).isNotNull();
        assertThat(done.failureReason()).isNull();
        ArgumentCaptor<SearchIndexJobFinishedEvent> event = ArgumentCaptor.forClass(SearchIndexJobFinishedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().status().phase()).isEqualTo(SearchIndexJobPhase.COMPLETED);
    }

    @Test
    @DisplayName("[#563] 재색인이 실패하면 FAILED · 실패한 단계 · 이유가 남고 끝 이벤트가 나감, 바로 다시 요청할 수 있음")
    void failedRebuildIsRecorded() {
        given(indexService.rebuild(any())).willAnswer(invocation -> {
            IndexProgress progress = invocation.getArgument(0);
            progress.phase(SearchIndexJobPhase.FILLING, "products_v1", "products_v2");
            throw new IllegalStateException("새 인덱스 products_v2를 채우다 3건이 실패해 재색인을 멈춤");
        });
        SearchIndexJobService service = service("A", new SyncTaskExecutor());

        Long jobId = service.requestRebuild().jobId();

        SearchIndexJobStatus failed = service.status(jobId);
        assertThat(failed.phase()).isEqualTo(SearchIndexJobPhase.FAILED);
        assertThat(failed.failedPhase()).isEqualTo(SearchIndexJobPhase.FILLING);
        assertThat(failed.failureReason()).contains("3건이 실패");
        assertThat(failed.finishedAt()).isNotNull();
        ArgumentCaptor<SearchIndexJobFinishedEvent> event = ArgumentCaptor.forClass(SearchIndexJobFinishedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().status().phase()).isEqualTo(SearchIndexJobPhase.FAILED);
        // 잠금이 풀려 다시 요청 가능
        assertThat(service.requestRebuild().jobId()).isNotEqualTo(jobId);
    }

    @Test
    @DisplayName("[#563] alias를 옮긴 뒤 실패하면 이유에 옛 인덱스를 직접 정리하라는 안내가 붙음")
    void failureAfterSwitchExplainsLeftoverIndex() {
        given(indexService.rebuild(any())).willAnswer(invocation -> {
            IndexProgress progress = invocation.getArgument(0);
            progress.phase(SearchIndexJobPhase.FILLING, "products_v1", "products_v2");
            progress.phase(SearchIndexJobPhase.SWITCHING_ALIAS, "products_v1", "products_v2");
            progress.phase(SearchIndexJobPhase.CATCHING_UP, "products_v1", "products_v2");
            throw new IllegalStateException("연결 끊김");
        });
        SearchIndexJobService service = service("A", new SyncTaskExecutor());

        SearchIndexJobStatus failed = service.status(service.requestRebuild().jobId());

        assertThat(failed.failedPhase()).isEqualTo(SearchIndexJobPhase.CATCHING_UP);
        assertThat(failed.failureReason()).contains("연결 끊김").contains("products_v2로 옮김").contains("products_v1");
    }

    @Test
    @DisplayName("[#563 완료 기준 2] 서버 두 대에서 동시에 재색인을 요청해도 작업은 하나만 등록되고 나머지는 409")
    void concurrentRequestsAcrossServers() throws Exception {
        // 실행하지 않는 실행자: 작업이 QUEUED로 남아 잠금을 쥔 상태
        SearchIndexJobService serverA = service("A", task -> { });
        SearchIndexJobService serverB = service("B", task -> { });
        int requests = 8;
        CyclicBarrier barrier = new CyclicBarrier(requests);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        List<Future<?>> futures = new ArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < requests; i++) {
                SearchIndexJobService server = i % 2 == 0 ? serverA : serverB;
                futures.add(pool.submit(() -> {
                    try {
                        barrier.await();
                        server.requestRebuild();
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(repository.count()).isEqualTo(1);
        assertThat(errors).hasSize(requests - 1).allSatisfy(error -> assertThat(error)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ProductErrorCode.SEARCH_INDEXING_IN_PROGRESS));
    }

    @Test
    @DisplayName("[#563] 다른 서버에서 재색인이 도는 동안 전체 색인 · 재색인 요청은 409, 끝나면 다시 가능")
    void reindexAllWaitsForRebuildOnOtherServer() throws Exception {
        CountDownLatch filling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(indexService.rebuild(any())).willAnswer(invocation -> {
            IndexProgress progress = invocation.getArgument(0);
            progress.phase(SearchIndexJobPhase.FILLING, "products_v1", "products_v2");
            filling.countDown();
            release.await();
            return new RebuildResult("products_v1", "products_v2", new ReindexResult(0, 0, 0, 0),
                    new ReindexResult(0, 0, 0, 0), true);
        });
        given(indexService.reindexAll(any())).willReturn(new ReindexResult(3, 1, 0, 0));
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try {
            SearchIndexJobService serverA = service("A", runner::execute);
            SearchIndexJobService serverB = service("B", new SyncTaskExecutor());
            Long rebuildId = serverA.requestRebuild().jobId();
            assertThat(filling.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(serverB::reindexAll)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProductErrorCode.SEARCH_INDEXING_IN_PROGRESS);
            assertThatThrownBy(serverB::requestRebuild).isInstanceOf(BusinessException.class);
            assertThat(serverB.status(rebuildId).phase()).isEqualTo(SearchIndexJobPhase.FILLING);

            release.countDown();
            verify(events, timeout(10_000)).publishEvent(any(SearchIndexJobFinishedEvent.class));

            assertThat(serverB.reindexAll().indexed()).isEqualTo(3);
            SearchIndexJobStatus reindexJob = serverB.recent().getFirst();
            assertThat(reindexJob.type()).isEqualTo(SearchIndexJobType.REINDEX_ALL);
            assertThat(reindexJob.phase()).isEqualTo(SearchIndexJobPhase.COMPLETED);
            assertThat(reindexJob.caughtUp()).isNull();
        } finally {
            release.countDown();
            runner.shutdownNow();
        }
    }

    @Test
    @DisplayName("[#563] 실행 서버가 죽어 하트비트가 2분 넘게 끊기면 다음 요청이 그 작업을 실패 처리하고 채우던 새 인덱스를 지운 뒤 등록")
    void staleJobIsFailedAndNewIndexDiscarded() {
        // 서버 A가 새 인덱스를 채우다 죽음 (실행자가 첫 배치까지만 하고 멈춘 상태를 기록으로 만듦)
        SearchIndexJobService serverA = service("A", task -> { });
        Long staleId = serverA.requestRebuild().jobId();
        tracker.start(staleId, "A", 100);
        tracker.progress(staleId, SearchIndexJobPhase.FILLING).phase(SearchIndexJobPhase.FILLING, "products_v1",
                "products_v2");
        given(searchIndex.currentIndex()).willReturn("products_v1");

        // 하트비트 2분 안: 아직 살아 있는 것으로 보고 409
        clock.advance(STALE_AFTER.minusSeconds(1));
        SearchIndexJobService serverB = service("B", task -> { });
        assertThatThrownBy(serverB::requestRebuild).isInstanceOf(BusinessException.class);

        clock.advance(Duration.ofSeconds(2));
        Long nextId = serverB.requestRebuild().jobId();

        SearchIndexJobStatus stale = serverB.status(staleId);
        assertThat(stale.phase()).isEqualTo(SearchIndexJobPhase.FAILED);
        assertThat(stale.failedPhase()).isEqualTo(SearchIndexJobPhase.FILLING);
        assertThat(stale.failureReason()).contains("하트비트").contains("A");
        verify(searchIndex).deleteIndex("products_v2");
        assertThat(serverB.status(nextId).phase()).isEqualTo(SearchIndexJobPhase.QUEUED);
        verify(events).publishEvent(any(SearchIndexJobFinishedEvent.class));
    }

    @Test
    @DisplayName("[#563] alias를 이미 옮긴 뒤 멈춘 작업은 실패 처리만 하고 인덱스는 지우지 않음")
    void staleJobAfterSwitchKeepsIndexes() {
        SearchIndexJobService serverA = service("A", task -> { });
        Long staleId = serverA.requestRebuild().jobId();
        tracker.start(staleId, "A", 100);
        tracker.progress(staleId, SearchIndexJobPhase.FILLING).phase(SearchIndexJobPhase.CATCHING_UP, "products_v1",
                "products_v2");
        clock.advance(STALE_AFTER.plusSeconds(1));

        serverA.failStaleJobs();

        SearchIndexJobStatus stale = serverA.status(staleId);
        assertThat(stale.phase()).isEqualTo(SearchIndexJobPhase.FAILED);
        assertThat(stale.failureReason()).contains("products_v2로 옮김");
        verify(searchIndex, never()).deleteIndex(any());
    }

    @Test
    @DisplayName("[#563] 실패 처리된 뒤 늦게 깨어난 실행은 다음 배치 기록에서 멈추고 결과를 덮지 않음")
    void abandonedRunCannotOverwrite() {
        SearchIndexJobService serverA = service("A", task -> { });
        Long jobId = serverA.requestRebuild().jobId();
        tracker.start(jobId, "A", 100);
        IndexProgress progress = tracker.progress(jobId, SearchIndexJobPhase.FILLING);
        clock.advance(STALE_AFTER.plusSeconds(1));
        serverA.failStaleJobs();

        assertThatThrownBy(() -> progress.batch(500, 0, 0)).isInstanceOf(SearchIndexJobAbandonedException.class);
        tracker.complete(jobId, true);

        SearchIndexJobStatus status = serverA.status(jobId);
        assertThat(status.phase()).isEqualTo(SearchIndexJobPhase.FAILED);
        assertThat(status.processed()).isZero();
        // 끝 이벤트는 실패 처리 때 한 번만
        verify(events).publishEvent(any(SearchIndexJobFinishedEvent.class));
    }

    @Test
    @DisplayName("[#563] 전체 색인이 실패하면 FAILED로 남기고 예외는 그대로 (지금처럼 500)")
    void failedReindexAll() {
        given(indexService.reindexAll(any())).willThrow(new IllegalStateException("OpenSearch 연결 실패"));
        SearchIndexJobService service = service("A", new SyncTaskExecutor());

        assertThatThrownBy(service::reindexAll).isInstanceOf(IllegalStateException.class);

        SearchIndexJobStatus job = service.recent().getFirst();
        assertThat(job.type()).isEqualTo(SearchIndexJobType.REINDEX_ALL);
        assertThat(job.phase()).isEqualTo(SearchIndexJobPhase.FAILED);
        assertThat(job.failureReason()).isEqualTo("OpenSearch 연결 실패");
    }

    @Test
    @DisplayName("[#563] 없는 작업을 조회하면 404 SEARCH_INDEX_JOB_NOT_FOUND")
    void unknownJob() {
        assertThatThrownBy(() -> service("A", new SyncTaskExecutor()).status(999_999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ProductErrorCode.SEARCH_INDEX_JOB_NOT_FOUND);
    }

    private SearchIndexJobService service(String worker, TaskExecutor executor) {
        return new SearchIndexJobService(repository, tracker, indexService, productRepository, searchIndex, executor,
                clock, STALE_AFTER, worker);
    }

    // 테스트에서 시간을 앞으로 돌리는 시계 (하트비트 끊김 흉내)
    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
