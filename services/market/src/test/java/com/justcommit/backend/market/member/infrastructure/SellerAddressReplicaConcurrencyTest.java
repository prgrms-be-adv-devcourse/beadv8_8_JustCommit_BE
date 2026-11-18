package com.justcommit.backend.market.member.infrastructure;

import com.justcommit.backend.market.member.domain.MemberReplica;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 회원에 판매자 배송지 저장과 탈퇴가 동시에 와도 최종 상태가 "탈퇴 + 주소 없음"인지 확인한다 (#275).
 * 동시 요청 후의 최종 상태 검증이다. 시작 신호를 맞춰도 DB 작업이 실제로 겹친다는 보장은 없어서,
 * 순서별 동작(주소 → 탈퇴, 탈퇴 → 주소)은 MemberReplicaRepositoryTest에서 확정한다.
 * 각 작업이 자기 트랜잭션으로 커밋해야 해서 테스트 트랜잭션은 끈다 (NOT_SUPPORTED).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SellerAddressReplicaConcurrencyTest {

  private static final OffsetDateTime ADDRESS_AT = OffsetDateTime.parse("2026-11-12T10:00:00+09:00");
  private static final OffsetDateTime WITHDRAWN_AT = OffsetDateTime.parse("2026-11-12T10:05:00+09:00");
  private static final int MEMBERS = 20;

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired
  private MemberReplicaRepository repository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @AfterEach
  void cleanUp() {
    repository.deleteAll();
  }

  @Test
  @DisplayName("주소 저장과 탈퇴가 동시에 와도 최종 상태는 탈퇴 + 주소 없음 (최종 상태 검증, 실제 겹침은 보장 안 함)")
  void concurrentAddressAndWithdrawal() throws Exception {
    for (long memberId = 1; memberId <= MEMBERS; memberId++) {
      long id = memberId;

      List<Object> results = runTogether(
          () -> inTransaction(() -> repository.upsertSellerAddresses(id, "v1.k1:addresses", ADDRESS_AT)),
          () -> inTransaction(() -> repository.markWithdrawn(id, WITHDRAWN_AT)));

      // 두 작업 모두 예외 없이 끝나야 한다 (예외면 결과 목록에 예외가 들어감)
      assertThat(results).allMatch(Integer.class::isInstance);
      MemberReplica replica = repository.findById(id).orElseThrow();
      assertThat(replica.isWithdrawn()).isTrue();
      assertThat(replica.getSellerAddressesEnc()).isNull();
    }
  }

  private Integer inTransaction(Supplier<Integer> work) {
    return new TransactionTemplate(transactionManager).execute(status -> work.get());
  }

  // 두 작업을 같은 순간에 시작시키고 각각의 결과(성공 값 또는 예외)를 돌려준다. 실제로 겹쳐 실행된다는 보장은 없다
  private List<Object> runTogether(Callable<?> first, Callable<?> second) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (Callable<?> task : List.of(first, second)) {
        futures.add(executor.submit(() -> {
          start.await();
          return task.call();
        }));
      }
      start.countDown();
      List<Object> results = new ArrayList<>();
      for (Future<?> future : futures) {
        try {
          results.add(future.get(10, TimeUnit.SECONDS));
        } catch (ExecutionException e) {
          results.add(e.getCause());
        }
      }
      return results;
    } finally {
      // 실패 · 시간 초과여도 작업을 멈추고 스레드가 끝날 때까지 기다린 뒤 정리로 넘어간다
      futures.forEach(future -> future.cancel(true));
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }
}
