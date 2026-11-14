package com.justcommit.backend.member.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.common.security.TokenException;
import com.justcommit.backend.member.address.domain.Address;
import com.justcommit.backend.member.address.infrastructure.AddressRepository;
import com.justcommit.backend.member.auth.application.LoginResult;
import com.justcommit.backend.member.auth.application.RefreshTokenIssuer;
import com.justcommit.backend.member.auth.infrastructure.jwt.JwtProvider;
import com.justcommit.backend.member.auth.infrastructure.jwt.SignupClaims;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.event.MemberCreated;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

// 소셜 회원가입: 가입 토큰 검증 → 중복 검사 → 회원·기본 배송지 저장 → 바로 로그인(토큰 발급)
@Service
@RequiredArgsConstructor
public class SocialSignupService {

  private final MemberRepository memberRepository;
  private final AddressRepository addressRepository;
  private final AccountCipher accountCipher;
  private final JwtProvider jwtProvider;
  private final RefreshTokenIssuer refreshTokenIssuer;
  private final OutboxPublisher outboxPublisher;
  private final DatabaseClock databaseClock;

  @Value("${jwt.access-token-expiration}")
  private Duration accessTokenExpiration;

  @Transactional
  public LoginResult signup(String signupToken, SocialSignupCommand command) {
    // 가입 토큰, 이메일·회원번호는 여기서만 꺼냄
    SignupClaims claims = parseSignupToken(signupToken);
    Provider provider = toProvider(claims.provider());
    String email = normalizeEmail(claims.email());

    if (memberRepository.findByProviderAndProviderId(provider, claims.providerId()).isPresent()) {
      throw new BusinessException(MemberErrorCode.DUPLICATE_SOCIAL_ACCOUNT);
    }
    if (memberRepository.existsByEmail(email)) {
      throw new BusinessException(MemberErrorCode.SOCIAL_EMAIL_CONFLICT);
    }
    if (memberRepository.existsByNickname(command.nickname())) {
      throw new BusinessException(MemberErrorCode.DUPLICATE_NICKNAME);
    }
    if (memberRepository.existsByPhone(command.phone())) {
      throw new BusinessException(MemberErrorCode.DUPLICATE_PHONE);
    }

    Member member = Member.createSocial(
            provider,
            claims.providerId(),
            email,
            command.nickname(),
            command.phone(),
            command.bankCode(),
            accountCipher.encrypt(command.accountNo()),
            command.accountHolder()
    );

    try {
      memberRepository.saveAndFlush(member);
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(toDuplicateErrorCode(e));
    }

    addressRepository.save(Address.createDefault(
            member,
            command.recipientName(),
            command.recipientPhone(),
            command.zipcode(),
            command.address1(),
            command.address2(),
            command.addressName()
    ));

    // 다른 서비스에 가입을 알림 (같은 트랜잭션에 저장, 커밋된 뒤에만 발행). 가입 직후는 판매자가 아님
    outboxPublisher.publish(MemberCreated.from(member, false));

    // 가입 후 바로 로그인
    return issueTokens(member);
  }

  // 토큰 오류는 BusinessException x, 401 반환
  // 만료·위조·Access 토큰(type 불일치)·헤더 없음 모두 같은 에러
  private SignupClaims parseSignupToken(String signupToken) {
    if (signupToken == null || signupToken.isBlank()) {
      throw new BusinessException(MemberErrorCode.INVALID_SIGNUP_TOKEN);
    }
    SignupClaims claims;
    try {
      claims = jwtProvider.parseSignupToken(signupToken);
    } catch (TokenException e) {
      throw new BusinessException(MemberErrorCode.INVALID_SIGNUP_TOKEN);
    }
    if (claims.email() == null || claims.providerId() == null) {
      throw new BusinessException(MemberErrorCode.INVALID_SIGNUP_TOKEN);
    }
    return claims;
  }

  // 토큰의 provider 문자열("KAKAO") → enum. 모르는 값이거나 LOCAL이면 잘못된 토큰
  private Provider toProvider(String provider) {
    if(provider == null) {
      throw new BusinessException(MemberErrorCode.INVALID_SIGNUP_TOKEN);
    }
    try {
      Provider result = Provider.valueOf(provider);
      if (result == Provider.LOCAL) {
        throw new BusinessException(MemberErrorCode.INVALID_SIGNUP_TOKEN);
      }
      return result;
    } catch (IllegalArgumentException e) {
      throw new BusinessException(MemberErrorCode.INVALID_SIGNUP_TOKEN);
    }
  }

  // 어떤 값이 겹쳤는지 구분
  private MemberErrorCode toDuplicateErrorCode(DataIntegrityViolationException e) {
    String message = String.valueOf(e.getMostSpecificCause().getMessage());
    if (message.contains("uk_member_provider")) {
      return MemberErrorCode.DUPLICATE_SOCIAL_ACCOUNT;
    }
    if (message.contains("uk_member_email")) {
      return MemberErrorCode.SOCIAL_EMAIL_CONFLICT;
    }
    if (message.contains("uk_member_nickname")) {
      return MemberErrorCode.DUPLICATE_NICKNAME;
    }
    if (message.contains("uk_member_phone")) {
      return MemberErrorCode.DUPLICATE_PHONE;
    }
    throw e;  // 예상하지 못한 제약 위반은 그대로 던짐 (500 + 로그)
  }

  // Access·Refresh 발급
  private LoginResult issueTokens(Member member) {
    // 새 회원 저장 후 같은 가입 트랜잭션에서 DB 시각을 읽어 Access 발급 시각으로 (#409)
    Instant issuedAt = databaseClock.now();
    String role = member.getRole().name();
    String accessToken = jwtProvider.createAccessToken(member.getId(), role, issuedAt);
    String refreshToken = refreshTokenIssuer.issue(member.getId(), member.getTokenVersion());

    return new LoginResult(
            member.getId(),
            accessToken,
            accessTokenExpiration.toSeconds(),
            member.getNickname(),
            role,
            refreshToken,
            RefreshTokenIssuer.REFRESH_TOKEN_TTL
    );
  }

  private String normalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }
}
