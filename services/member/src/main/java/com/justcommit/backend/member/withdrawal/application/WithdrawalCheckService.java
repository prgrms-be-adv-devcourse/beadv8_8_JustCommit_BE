package com.justcommit.backend.member.withdrawal.application;

import com.justcommit.backend.common.client.exception.InternalApiException;
import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.member.withdrawal.infrastructure.client.MarketWithdrawalApi;
import com.justcommit.backend.member.withdrawal.infrastructure.client.PaymentWithdrawalApi;
import com.justcommit.backend.member.withdrawal.infrastructure.client.SettlementWithdrawalApi;
import com.justcommit.backend.member.withdrawal.infrastructure.client.WithdrawalCheckResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

// 탈퇴 가능 여부 확인 (#406): market · payment · settlement 탈퇴 확인 내부 API를 병렬로 불러 결과를 모은다
// - 각 호출에는 공통 타임아웃 · 연결 실패 재시도가 적용된다. 전체 소요 시간은 연결 · 응답 · 재시도 상황에 따라 달라진다
// - 이 서비스는 트랜잭션을 시작하지 않는다. 호출하는 쪽(#407)도 트랜잭션 · 회원 잠금을 시작하기 전에 부른다
// - 한계: 다른 스레드에서 호출하므로 로그의 traceId가 이어지지 않을 수 있다 (공통 관측 작업에서 다룸)
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalCheckService {

  static final String MARKET = "market";
  static final String PAYMENT = "payment";
  static final String SETTLEMENT = "settlement";

  private final MarketWithdrawalApi marketApi;
  private final PaymentWithdrawalApi paymentApi;
  private final SettlementWithdrawalApi settlementApi;

  // authorization: 사용자 요청의 Authorization. 병렬 호출 전에 받아 각 호출에 그대로 넘긴다 (다른 스레드에서는 자동 전달이 안 됨)
  public WithdrawalCheckResult check(Long memberId, String authorization) {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // 세 작업을 먼저 모두 제출한 뒤 결과를 기다린다
      Future<Outcome> market = executor.submit(
          () -> checkOne(MARKET, memberId, () -> marketApi.check(memberId, authorization)));
      Future<Outcome> payment = executor.submit(
          () -> checkOne(PAYMENT, memberId, () -> paymentApi.check(memberId, authorization)));
      Future<Outcome> settlement = executor.submit(
          () -> checkOne(SETTLEMENT, memberId, () -> settlementApi.check(memberId, authorization)));

      return combine(List.of(await(market), await(payment), await(settlement)));
    }
  }

  // 서비스 한 곳 호출. 예상한 통신 · 응답 해석 오류만 실패로 바꾸고, 그 밖의 예외는 숨기지 않는다
  private Outcome checkOne(String service, Long memberId,
                            Supplier<ApiResponse<WithdrawalCheckResponse>> call) {
    ApiResponse<WithdrawalCheckResponse> response;
    try {
      response = call.get();
    } catch (InternalApiException | RestClientException e) {
      // InternalApiException: 4xx · 5xx · 연결 불가 · 응답 시간 초과 (common-web), RestClientException: 본문 읽기 · 형식 오류
      log.warn("탈퇴 확인 호출 실패: service={}, memberId={}, error={}", service, memberId, e.getClass().getSimpleName());
      return new Failed(service);
    }
    return evaluate(service, memberId, response);
  }
  // 합의한 응답 형식인지 확인한다. 정상 응답의 판단 기준은 hasWithdrawalBlocker (사유로 다시 판단하지 않음)
  private Outcome evaluate(String service, Long memberId, ApiResponse<WithdrawalCheckResponse> response) {
    WithdrawalCheckResponse data = (response == null) ? null : response.data();
    if (data == null || data.hasWithdrawalBlocker() == null || data.blockingReasons() == null) {
      log.warn("탈퇴 확인 응답 형식 오류(값 누락): service={}, memberId={}", service, memberId);
      return new Failed(service);
    }

    boolean blocked = data.hasWithdrawalBlocker();
    boolean hasReasons = !data.blockingReasons().isEmpty();
    if (blocked != hasReasons) {
      // false + 사유 있음, true + 빈 사유: 차단 여부와 사유가 맞지 않아 정상 응답으로 믿을 수 없음
      log.warn("탈퇴 확인 응답 형식 오류(차단 여부와 사유 불일치): service={}, memberId={}", service, memberId);
      return new Failed(service);
    }

    return blocked
        ? new Blocked(service, data.blockingReasons(), data.details())
        : new Passed(service);
  }

  // 실패 → 차단 → 통과 순으로 판단. 실패가 있어도 받은 차단 사유는 남긴다
  private static WithdrawalCheckResult combine(List<Outcome> outcomes) {
    List<WithdrawalCheckResult.ServiceBlocker> blockers = new ArrayList<>();
    List<String> failedServices = new ArrayList<>();
    for (Outcome outcome : outcomes) {
      switch (outcome) {
        case Blocked b -> blockers.add(new WithdrawalCheckResult.ServiceBlocker(b.service(), b.reasons(), b.details()));
        case Failed f -> failedServices.add(f.service());
        case Passed p -> { }
      }
    }

    WithdrawalCheckStatus status;
    if (!failedServices.isEmpty()) {
      status = WithdrawalCheckStatus.UNAVAILABLE;
    } else if (!blockers.isEmpty()) {
      status = WithdrawalCheckStatus.BLOCKED;
    } else {
      status = WithdrawalCheckStatus.ALLOWED;
    }
    return new WithdrawalCheckResult(status, List.copyOf(blockers), List.copyOf(failedServices));
  }

  // 예상한 오류는 작업 안에서 이미 실패로 바뀌므로, 여기로 오는 예외는 예상 밖 → 원인을 그대로 다시 던진다
  private static Outcome await(Future<Outcome> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      // 인터럽트 표시를 복원하고 중단
      Thread.currentThread().interrupt();
      throw new IllegalStateException("탈퇴 확인을 기다리는 중 중단되었습니다.", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("탈퇴 확인 중 예상하지 못한 오류가 발생했습니다.", cause);
    }
  }

  // 서비스 한 곳의 판정
  private sealed interface Outcome permits Passed, Blocked, Failed {
  }

  private record Passed(String service) implements Outcome {
  }

  private record Blocked(String service, List<String> reasons, Map<String, Object> details) implements Outcome {
  }

  private record Failed(String service) implements Outcome {
  }
}
