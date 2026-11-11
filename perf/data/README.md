# 부하 측정 테스트 데이터 (#351)

회원가입 · 충전 · 카카오 로그인은 부하 시나리오에서 빼고, 필요한 데이터를 SQL로 미리 넣는다.

| 파일 | 내용 |
|---|---|
| `seed.sql` | 구매자 · 판매자 · 지갑 · 판매 중 상품 · 배송 완료 4일 지난 주문 (개수는 실행할 때 정함) |

## 넣기 전에

- 앱이 한 번 떠서 테이블, 시스템 계정(HOLDING 등)과 그 지갑, 품종이 들어가 있어야 한다.
- 이미 `perf-…@perf.test` 계정이 있으면 멈춘다 → 백업을 복원한 뒤 다시 넣는다.

## 넣기 (서버)

비밀번호는 레포에 남기지 않는다. 실행할 때 정하고 bcrypt 해시만 넘긴다. k6에는 같은 비밀번호를 환경변수로 준다.

```bash
cd ~/replant
read -rs PERF_PASSWORD                       # 테스트 계정 비밀번호 (화면에 안 보임)
HASH=$(docker run --rm httpd:2.4-alpine htpasswd -nbBC 10 x "$PERF_PASSWORD" | cut -d: -f2 | tr -d '\r\n')
docker compose -f docker-compose.prod.yml exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  -v pw_hash="$HASH" -v buyers=2000 -v sellers=200 -v products=10000 -v delivered=500 < perf/data/seed.sql
```

로컬(빈 DB + 앱 한 번 기동)에서 2000 · 200 · 10000 · 500으로 5초 안에 끝났다.

## 확인한 것 (로컬, 2026-11-11)

| 확인 | 결과 |
|---|---|
| 테스트 구매자 로그인 | 200 (틀린 비밀번호 401) |
| 상품 목록 · 검색 | 200 |
| 장바구니 → 주문 | PAID, 구매자 잔액 −23,000 · HOLDING +23,000 |
| 자동 구매확정 (perf 프로필 1분 주기) | 확정됨 (로컬은 Kafka가 없어 건당 60초 대기 · 정산 이벤트 미발행 → 서버에서 다시 확인) |
