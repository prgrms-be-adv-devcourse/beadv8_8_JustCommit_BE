# 서비스 간 REST 규칙

> 언제 읽나: 다른 서비스를 REST로 부르거나 내부 API를 만들 때
> 상태: ⬜ 작성 전 · 마지막 수정 2026-11-04 · 관련: Miro 7 · 22, 노션 기획안 「REST 호출」

## 언제 REST, 언제 이벤트

<!-- 채울 내용: 결과가 필요하면 REST, 알리기만 하면 이벤트 -->

## 경로와 보호

<!-- 채울 내용: /internal/v1, Gateway 라우팅 제외, 포트 미개방, X-Internal-Key -->

## 클라이언트

<!-- 채울 내용: RestClient + @HttpExchange, Docker 서비스 이름 주소 -->

## 멱등 키

<!-- 채울 내용: Idempotency-Key = 업무 ID -->

## 에러와 재시도

<!-- 채울 내용: ApiResponse 에러, 4xx 재시도 안 함 → resilience.md -->
