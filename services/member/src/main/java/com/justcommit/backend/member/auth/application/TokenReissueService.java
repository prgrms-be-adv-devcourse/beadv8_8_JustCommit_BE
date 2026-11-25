package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.infrastructure.jwt.JwtProvider;
import com.justcommit.backend.member.auth.infrastructure.redis.RefreshTokenOwner;
import com.justcommit.backend.member.auth.infrastructure.redis.RefreshTokenRedisStore;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.lock.MemberLocker;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

// 토큰 재발급 (Refresh Token Rotation)
@Service
@RequiredArgsConstructor
public class TokenReissueService {

  private final RefreshTokenRedisStore refreshTokenStore;
  private final RefreshTokenIssuer refreshTokenIssuer;
  private final MemberLocker memberLocker;
  private final JwtProvider jwtProvider;
  private final AccessIssueTimeGuard accessIssueTimeGuard;

  @Value("${jwt.access-token-expiration}")
  private Duration accessTokenExpiration;

  // 트랜잭션은 AccessIssueTimeGuard가 연다 (#546): 폐기와 같은 초면 롤백하고(회원 잠금 해제) 다음 초까지 기다린 뒤
  // 아래 처리를 처음부터 다시 실행한다. 그래서 이 메서드에는 @Transactional을 붙이지 않는다
  public TokenReissueResult reissue(String refreshToken) {
    // 쿠키X -> 바로 401(Redis를 조회x)
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new BusinessException(MemberErrorCode.INVALID_REFRESH_TOKEN);
    }
    return accessIssueTimeGuard.runWithRetry(() -> reissueInTransaction(refreshToken));
  }

  // 재발급: Refresh 읽기 → 회원 행 잠금 → 상태 · 인증 버전 확인 → 발급 시각 확인 → Refresh 꺼내며 삭제 → 새 Access · Refresh 발급 → 커밋
  // 비밀번호 변경 · 탈퇴(같은 회원 행 잠금)와 겹치면 한쪽이 끝날 때까지 기다리고, 잠근 뒤 읽은 값으로 판단 (#409)
  private TokenReissueResult reissueInTransaction(String refreshToken) {
    // 잠글 회원을 찾기 위해 읽기만 (옛 형식 · 잘못된 값도 여기서 거절)
    RefreshTokenOwner found = refreshTokenStore.findOwner(refreshToken)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.INVALID_REFRESH_TOKEN));
    Member member = memberLocker.lockById(found.memberId())
        .orElseThrow(() -> new BusinessException(MemberErrorCode.INVALID_REFRESH_TOKEN));

    // 꺼내기 전 확인 (#546): 거절될 요청은 여기서 끝낸다 (발급 시각을 기다리지 않고, Refresh도 꺼내지 않음)
    // 탈퇴 · 카카오 연결 해제(INACTIVE, #422) 회원은 재발급하지 않음 (없는 회원과 같은 응답)
    if (member.getStatus() != MemberStatus.ACTIVE) {
      throw new BusinessException(MemberErrorCode.INVALID_REFRESH_TOKEN);
    }
    // 비밀번호 변경 · 탈퇴로 인증 버전이 올라갔으면, 그 전에 발급한 Refresh는 거절 (#409)
    if (found.tokenVersion() != member.getTokenVersion()) {
      throw new BusinessException(MemberErrorCode.INVALID_REFRESH_TOKEN);
    }

    // 회원 잠금과 확인을 마친 뒤 DB 시각을 읽어 Access 발급 시각으로 (#409)
    // 최근 폐기 시각과 같은 초면 여기서 중단된다. Refresh를 꺼내기 전이라 다시 실행할 때 그대로 쓸 수 있다 (#546)
    Instant issuedAt = accessIssueTimeGuard.issuableNow(member.getId());

    // 발급할 수 있을 때만 꺼내며 삭제 → 같은 토큰으로 재발급 불가. 그 사이 다른 요청이 썼으면 없음
    RefreshTokenOwner owner = refreshTokenStore.findAndDelete(refreshToken)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.INVALID_REFRESH_TOKEN));
    // 꺼낸 토큰이 잠근 회원의 것인지 (정상 저장 경로에선 바뀌지 않지만, 다른 회원을 잠근 채 발급하지 않도록)
    if (owner.memberId() != member.getId()) {
      throw new BusinessException(MemberErrorCode.INVALID_REFRESH_TOKEN);
    }
    // 최종 판단은 실제로 꺼낸 값(owner)의 버전으로
    if (owner.tokenVersion() != member.getTokenVersion()) {
      throw new BusinessException(MemberErrorCode.INVALID_REFRESH_TOKEN);
    }

    // role은 토큰이 아니라 DB에서 다시 조회(판매자 등록 후 SELLER 반영)
    String accessToken = jwtProvider.createAccessToken(member.getId(), member.getRole().name(), issuedAt);
    String newRefreshToken = refreshTokenIssuer.issue(member.getId(), member.getTokenVersion());

    return new TokenReissueResult(
        accessToken,
        accessTokenExpiration.toSeconds(),
        newRefreshToken,
        RefreshTokenIssuer.REFRESH_TOKEN_TTL
    );
  }
}
