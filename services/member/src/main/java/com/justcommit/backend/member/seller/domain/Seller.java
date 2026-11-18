package com.justcommit.backend.member.seller.domain;

import com.justcommit.backend.member.domain.Member;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

// 판매자 정보. 회원과 1:1이며 회원 id를 그대로 PK로 사용 (별도 seller id 없음)
@Entity
@Table(name = "seller")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seller {
  // PK = member.id. 값은 @MapsId가 member에서 채워 줌 (직접 넣지 않음)
  @Id
  private Long memberId;

  @MapsId
  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "member_id", foreignKey = @ForeignKey(name = "fk_seller_member"))
  private Member member;

  @Column(length = 500)
  private String intro;

  // 판매자 등록 때 동의한 상품 검수(외부 AI 이용 · 국외 이전) 문구 버전과 시각. 등록 후 바꾸지 않음
  @Column(name = "review_consent_version", length = 30, nullable = false, updatable = false)
  private String reviewConsentVersion;

  @Column(name = "review_consented_at", nullable = false, updatable = false)
  private LocalDateTime reviewConsentedAt;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  private Seller(
      Member member, String intro, String reviewConsentVersion, LocalDateTime reviewConsentedAt) {
    this.member = member;
    this.intro = intro;
    this.reviewConsentVersion = reviewConsentVersion;
    this.reviewConsentedAt = reviewConsentedAt;
  }

  public static Seller register(
      Member member, String intro, String reviewConsentVersion, LocalDateTime reviewConsentedAt) {
    return new Seller(member, intro, reviewConsentVersion, reviewConsentedAt);
  }

  // 소개글 변경 (null이면 삭제). 길이는 요청 검증이 맡음
  public void changeIntro(String intro) {
    this.intro = intro;
  }
}
