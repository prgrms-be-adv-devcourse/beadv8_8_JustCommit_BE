package com.justcommit.backend.member.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 배송지 (id, created_at, updated_at은 BaseTimeEntity에서 상속)
@Entity
@Table(name = "address")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Address extends BaseTimeEntity {

  // 회원 ID만 저장 (LFK, DB FK 제약 없음)
  @Column(name = "member_id", nullable = false)
  private Long memberId;

  @Column(nullable = false, length = 5)
  private String zipcode; // 우편번호 5자리

  @Column(nullable = false, length = 200)
  private String address1; // 도로명 주소(기본주소)

  @Column(length = 100)
  private String address2; // 상세 주소, NULL 가능

  // 회원당 true 1개는 서비스에서 보장 (DB 부분 UNIQUE 인덱스는 보류)
  @Column(name = "is_default", nullable = false)
  private boolean isDefault;

  @Column(name = "address_name", length = 20)
  private String addressName; // 배송지 별칭(집, 회사), NULL 가능

  @Column(name = "recipient_name", nullable = false, length = 20)
  private String recipientName;

  @Column(name = "recipient_phone", nullable = false, length = 11)
  private String recipientPhone; // 숫자만 저장

  // 회원가입 시 첫 배송지 = 기본 배송지
  public static Address createDefault(Long memberId, String recipientName, String recipientPhone,
                                      String zipcode, String address1, String address2, String addressName) {
    Address address = new Address();
    address.memberId = memberId;
    address.recipientName = recipientName;
    address.recipientPhone = recipientPhone;
    address.zipcode = zipcode;
    address.address1 = address1;
    address.address2 = address2;
    address.addressName = addressName;
    address.isDefault = true;
    return address;
  }
}
