package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.infrastructure.jwt.JwtProvider;
import com.justcommit.backend.member.auth.infrastructure.redis.RefreshTokenOwner;
import com.justcommit.backend.member.auth.infrastructure.redis.RefreshTokenRedisStore;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

// 토큰 재발급 (Refresh Token Rotation)
@Service
@RequiredArgsConstructor
public class TokenReissueService {

  private final RefreshTokenRedisStore refreshTokenStore;
  private final RefreshTokenIssuer refreshTokenIssuer;
  private final MemberRepository memberRepository;
  private final JwtProvider jwtProvider;
  private final DatabaseClock databaseClock;

  @Value("${jwt.access-token-expiration}")
  private Duration accessTokenExpiration;

  // 재발급: Refresh 읽기 → 회원 행 잠금 → Refresh 꺼내며 삭제 → 상태 · 인증 버전 확인 → 새 Access · Refresh 발급 → 커밋
  // 비밀번호 변경 · 탈퇴(같은 회원 행 잠금)와 겹치면 한쪽이 끝날 때까지 기다리고, 잠근 뒤 읽은 값으로 판단 (#409)
  @Transactional
  public TokenReissueResult reissue(String refreshToken) {
    // 쿠키X -> 바로 401(Redis를 조회x)
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new BusinessException(MemberErrorCode.INVALID_REFRESH_TOKEN);
    }

    // 잠글 회원을 찾기 위해 읽기만 (옛 형식 · 잘못된 값도 여기서 거절). 이 값으로 버전을 판단하지 않음
    RefreshTokenOwner found = refreshTokenStore.findOwner(refreshToken)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.INVALID_REFRESH_TOKEN));
    Member member = memberRepository.findByIdForUpdate(found.memberId())
        .orElseThrow(() -> new BusinessException(MemberErrorCode.INVALID_REFRESH_TOKEN));

    // 잠근 뒤 꺼내며 삭제 → 같은 토큰으로 재발급 불가. 그 사이 다른 요청이 썼으면 없음
    RefreshTokenOwner owner = refreshTokenStore.findAndDelete(refreshToken)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.INVALID_REFRESH_TOKEN));
    // 꺼낸 토큰이 잠근 회원의 것인지 (정상 저장 경로에선 바뀌지 않지만, 다른 회원을 잠근 채 발급하지 않도록)
    if (owner.memberId() != member.getId()) {
      throw new BusinessException(MemberErrorCode.INVALID_REFRESH_TOKEN);
    }
    // 탈퇴 회원은 재발급하지 않음 (없는 회원과 같은 응답)
    if (member.getStatus() == MemberStatus.DELETED) {
      throw new BusinessException(MemberErrorCode.INVALID_REFRESH_TOKEN);
    }
    // 비밀번호 변경 · 탈퇴로 인증 버전이 올라갔으면, 그 전에 발급한 Refresh는 거절 (#409)
    // 비교는 실제로 꺼낸 값(owner)의 버전으로
    if (owner.tokenVersion() != member.getTokenVersion()) {
      throw new BusinessException(MemberErrorCode.INVALID_REFRESH_TOKEN);
    }
    // 회원 잠금과 모든 확인을 마친 뒤 DB 시각을 읽어 Access 발급 시각으로 (#409)
    Instant issuedAt = databaseClock.now();
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
