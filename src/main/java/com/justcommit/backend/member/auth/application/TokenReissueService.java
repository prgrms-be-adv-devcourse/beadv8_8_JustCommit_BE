package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.security.JwtProvider;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.auth.infrastructure.redis.RefreshTokenRedisStore;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

// 토큰 재발급 (Refresh Token Rotation)
@Service
@RequiredArgsConstructor
public class TokenReissueService {

  private final RefreshTokenRedisStore refreshTokenStore;
  private final RefreshTokenIssuer refreshTokenIssuer;
  private final MemberRepository memberRepository;
  private final JwtProvider jwtProvider;

  @Value("${jwt.access-token-expiration}")
  private Duration accessTokenExpiration;

  // 재발급: 기존 Refresh 조회·삭제(GETDEL) → 회원 재조회 → 새 Access·Refresh 발급
  @Transactional(readOnly = true)
  public TokenReissueResult reissue(String refreshToken) {
    // 쿠키X -> 바로 401(Redis를 조회x)
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new BusinessException(MemberErrorCode.INVALID_REFRESH_TOKEN);
    }

    // 기존 토큰 폐기 → 같은 토큰으로 재발급 불가
    Long memberId = refreshTokenStore.findAndDelete(refreshToken)
            .orElseThrow(() -> new BusinessException(MemberErrorCode.INVALID_REFRESH_TOKEN));

    // role은 토큰이 아니라 DB에서 다시 조회(판매자 등록 후 SELLER 반영)
    Member member = memberRepository.findById(memberId)
            .orElseThrow(() -> new BusinessException(MemberErrorCode.INVALID_REFRESH_TOKEN));

    String accessToken = jwtProvider.createAccessToken(member.getId(), member.getRole().name());
    String newRefreshToken = refreshTokenIssuer.issue(member.getId());

    return new TokenReissueResult(
            accessToken,
            accessTokenExpiration.toSeconds(),
            newRefreshToken,
            RefreshTokenIssuer.REFRESH_TOKEN_TTL
    );
  }
}
