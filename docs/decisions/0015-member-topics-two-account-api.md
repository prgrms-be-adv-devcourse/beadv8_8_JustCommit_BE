# 0015. 회원 토픽 2개, 계좌는 member 내부 API로 조회

> 상태: ✅ 확정 · 결정일 2026-11-05 · 결정한 사람: member · 결제 담당
> 대체: 0004 · 대체됨: 0019 (주소 토픽 부분만)
## 배경

ADR 0004는 계좌를 `member.account.events.v1`로 payment에 복제했습니다. 계좌는 민감 정보이고, 정산금은 지갑으로 들어가 계좌가 필요한 때는 인출뿐입니다.

## 결정

회원 토픽은 `member.events.v1`과 `member.address.events.v1`(판매자 기본 배송지만, 암호화) 두 개입니다. 계좌는 복제하지 않고 payment가 인출을 신청할 때 member 내부 API로 받아 인출 건에 암호화해 저장합니다. 도메인 토픽은 8개가 됩니다.

## 고른 이유

계좌 원문이 여러 서비스 DB에 퍼지지 않고, 인출 때만 필요해 동기 호출로 충분합니다.

## 다른 안

| 안 | 버린 이유 |
|---|---|
| 계좌 이벤트 복제 (0004) | 민감 정보가 payment DB에 상시 남음 |

## 영향

[Kafka 토픽 표](../reference/kafka-topics.md), [가입과 회원 복제본](../flows/signup-and-replica.md), [payment](../services/payment.md)
