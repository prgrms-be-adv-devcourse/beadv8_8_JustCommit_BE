# 0004. 회원 복제본과 회원 토픽 3개

> 상태: ✅ 확정 · 결정일 2026-11-03 · 결정한 사람: 팀 회의
> 대체: - · 대체됨: -

## 배경

여러 서비스가 회원 닉네임 · 배송지 · 계좌를 읽어야 합니다. 매번 member를 부르면 member 장애가 전체로 번집니다.

## 결정

product · market · payment · settlement가 `MEMBER_REPLICA`를 두고 회원 이벤트로 채웁니다. 토픽은 `member.events.v1` · `member.address.events.v1` · `member.account.events.v1` 세 개로 나눠 배송지는 market, 계좌는 payment만 받게 합니다. 복제본이 없으면 JWT memberId로 get-or-create합니다.

## 고른 이유

member가 죽어도 주문 · 결제가 동작하고, 계좌처럼 민감한 정보는 필요한 서비스만 받습니다. 기본 배송지 · 회원 조회 내부 API가 필요 없어집니다.

## 다른 안

| 안 | 버린 이유 |
|---|---|
| member 내부 REST 조회 | member 장애가 전파되고 호출이 많음 |
| 회원 토픽 1개 | 모든 구독자가 계좌 · 주소까지 받게 됨 |

## 영향

[가입과 회원 복제본](../flows/signup-and-replica.md), [Kafka 토픽 표](../reference/kafka-topics.md)
