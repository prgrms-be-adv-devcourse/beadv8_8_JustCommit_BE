package com.justcommit.backend.member.domain;

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

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  private Seller(Member member, String intro) {
    this.member = member;
    this.intro = intro;
  }

  public static Seller register(Member member, String intro) {
    return new Seller(member, intro);
  }
}
