# 회원 탈퇴 응답 시간 · 장애 처리 (#531)

Gateway · member · market · payment · settlement를 실제로 이은 로컬 환경에서 탈퇴 POST의 응답 시간과 장애 때의 응답 · 회원 상태를 확인한 기록이다. 대량 부하 · 처리량 측정이 아니다.
결과는 **로컬 회원 정상**, **카카오 모의**, **장애**로 나눠 적고 서로 합쳐 평균 내지 않는다. 표본이 적어 p95는 쓰지 않는다.

| 항목 | 상태 |
|---|---|
| 로컬 회원 정상 경로 | 측정 (10회) |
| 카카오 회원 정상 · 오류 · 시간 초과 | **모의 카카오로만** 측정. 실제 카카오 API는 **미실행** |
| 장애: 탈퇴 확인 한 곳 실패 · 지연, `MEMBER_BUSY`, 회원 단위 폐기 기록 실패, Gateway 504 | 측정 (각 3회) |
| 구간별 시간 (3곳 병렬 확인 · 카카오 연결 해제 · 최종 DB · Redis 처리) | **미측정**. 탈퇴 구간 계측이 없어 전체 POST 시간만 있음 |

## 측정 환경

| 항목 | 값 |
|---|---|
| 측정일 | 2026-11-26 |
| 코드 | `develop` `73969514` (bootJar), 운영 코드 · Gateway 설정 수정 없음 |
| 기계 | Apple M1 Pro 10코어 · 메모리 16GB, macOS 14.8. 요청 도구 · 서비스 5개 · 인프라가 한 기계 |
| 서비스 | gateway 18080 · member 18081 · market 18083 · payment 18084 · settlement 18085, JDK 25, `TZ=Asia/Seoul` |
| 인프라 | 측정 전용 컨테이너: PostgreSQL `pgvector/pgvector:pg17` 15432 (tmpfs, `docker/postgres/01-service-databases.sql`로 서비스 DB 생성) · Redis `redis:8-alpine` 16379 · Kafka `apache/kafka:4.2.1` 19092. 개발용 5432 · 6379 · 9092 · 8080 · 8081과 연결이 없는 것을 측정 전후에 확인 |
| 자원 제한 (확인된 것) | 서비스 JVM마다 `-Xmx512m`. 컨테이너별 CPU · 메모리 제한은 걸지 않음 (Docker Desktop VM 전체 CPU 10 · 메모리 약 7.7GiB) |
| 타임아웃 | 운영 설정 그대로 → [장애 처리 규칙](../../docs/conventions/resilience.md) (Gateway 탈퇴 라우트 20초, 탈퇴 확인 1초 / 3초 · 연결 실패만 1회 재시도, 카카오 1초 / 3초, 회원 잠금 3초) |
| 메일 | member 메일 health 끔 (`MANAGEMENT_HEALTH_MAIL_ENABLED=false`). 카카오 측정만 메일 서버를 로컬 SMTP 수신기(Mailpit)로 바꿈 |
| 회원 | 회차마다 새 회원을 측정 DB에 직접 넣음 (탈퇴한 회원 재사용 없음). 주문 · 지갑 · 정산이 없어 3곳 확인은 모두 `ALLOWED` |
| 요청 | `curl`로 Gateway(`127.0.0.1`)에 보내고 `time_total`을 기록. 로그인 요청 제한(IP당 1분 5회) 때문에 회차마다 다른 `X-Forwarded-For` |
| 워밍업 | 로컬 회원 정상 3회, 카카오 모의 · `MEMBER_BUSY` · 폐기 기록 실패 · Gateway 504 측정 전 각 1회 (결과에서 제외). settlement 장애 측정은 워밍업 없음 |

환경변수 (이름만): `JWT_PRIVATE_KEY` · `JWT_PUBLIC_KEY` · `ACCOUNT_ENCRYPTION_KEY` · `MEMBER_HMAC_KEY` · `MEMBER_ADDRESS_EVENT_KEY` · `OAUTH2_STATE_SECRET` · `PAYMENT_KEY_ENCRYPTION_KEY` · `CASH_OUT_ACCOUNT_ENCRYPTION_KEY` · `INTERNAL_API_KEY`(측정마다 새로 만든 값), `KAKAO_CLIENT_ID` · `KAKAO_CLIENT_SECRET` · `KAKAO_ADMIN_KEY`(기동용 임의 값), `SERVER_PORT` · `DB_URL` · `REDIS_HOST` · `REDIS_PORT` · `KAFKA_BOOTSTRAP_SERVERS`, 서비스 주소 `MEMBER_` · `MARKET_` · `PAYMENT_` · `SETTLEMENT_` · `PRODUCT_` · `NOTIFICATION_` · `AI_SERVICE_URL`(모두 측정 포트), `COURIER_BASE_URL`(닫힌 포트).

## 로컬 회원 정상 경로

회차: 로그인 → `GET users/me/withdrawal`(200 `ALLOWED`) → `POST users/me/withdrawal` `{ password }` → 같은 Access로 `GET users/me/withdrawal`.
워밍업 3회와 그 전의 연결 확인 1회는 제외했다.

| 표본 | 평균 | 중앙값 | 최솟값 | 최댓값 | Gateway 20초 대비 최댓값 |
|---|---|---|---|---|---|
| 10 | 0.127초 | 0.127초 | 0.119초 | 0.134초 | 0.67% |

10회 모두 200 `revocationCompleted: true`, 기존 Access는 바로 Gateway 401 `INVALID_TOKEN`.

## 카카오 회원 (모의 카카오)

**실제 카카오가 아니다.** 카카오 연결 해제 `POST /v1/user/unlink`만 WireMock으로 바꿨다 (member만 `SPRING_APPLICATION_JSON`으로 `member.kakao.api-base-url`을 덮어씀). 카카오 로그인은 거치지 않았다.

| 준비 | 방법 (`WithdrawalVerificationIntegrationTest`와 같은 방식) |
|---|---|
| 카카오 회원 | `provider=KAKAO` 회원 행을 측정 DB에 넣음 |
| Access | `JwtProvider.createAccessToken`과 같은 클레임(`sub` · `type=ACCESS` · `role` · `iat` · `exp`)을 측정용 키로 서명 |
| 탈퇴 토큰 | 실제 `POST users/me/withdrawal/verifications` → 로컬 SMTP 수신기에서 인증번호 → `POST users/me/withdrawal/tokens` |

| 모의 응답 | 표본 | Gateway 응답 | 평균 | 중앙값 | 최댓값 | 모의 호출 |
|---|---|---|---|---|---|---|
| 200 | 10 (워밍업 1회 제외) | 200 `revocationCompleted: true` 10/10 | 0.056초 | 0.050초 | 0.095초 | 1회, 폼 `target_id`가 회원 `provider_id` |
| 500 | 3 | 503 `SOCIAL_UNLINK_FAILED` 3/3 | 0.041초 | 0.034초 | 0.061초 | 1회 (재시도 없음) |
| 5초 지연 (응답 3초 초과) | 3 | 503 `SOCIAL_UNLINK_FAILED` 3/3 | 3.044초 | 3.042초 | 3.056초 | 1회 (재시도 없음) |

500 · 시간 초과 뒤 같은 탈퇴 토큰을 다시 보내면 400 `INVALID_WITHDRAWAL_TOKEN` 6/6 (연결 해제 직전에 꺼내 소모됨, [외부 API](../../docs/reference/external-apis.md#회원-탈퇴)와 같음).
모의 200이 로컬 회원보다 빠른 것은 카카오 회원 재확인에 비밀번호 검사(BCrypt)가 없기 때문으로 본다 (추정, 구간 계측 없음).

## 장애 경로

모두 로컬 회원, 회차마다 새 회원, 3회. 응답 시간은 Gateway 기준 `time_total`.

| 장애 | 주입 방법 | Gateway 응답 | 평균 / 최댓값 |
|---|---|---|---|
| settlement 탈퇴 확인 503 | member만 settlement 주소를 WireMock 프록시로. POST 직전에만 `withdrawalCheck`에 503 응답 | 503 `WITHDRAWAL_CHECK_UNAVAILABLE` 3/3, settlement 호출 1회 | 0.118 / 0.135초 |
| settlement 연결 거부 | 프록시 컨테이너 정지 | 503 `WITHDRAWAL_CHECK_UNAVAILABLE` 3/3 | 0.330 / 0.339초 |
| settlement 응답 5초 지연 | 프록시 응답 지연 (응답 3초 초과) | 503 `WITHDRAWAL_CHECK_UNAVAILABLE` 3/3, 호출 1회 | 3.106 / 3.109초 |
| settlement 응답 2초 지연 | 프록시 응답 지연 (3초 안) | 200 `revocationCompleted: true` 3/3 (탈퇴 완료) | 2.143 / 2.162초 |
| 회원 잠금 대기 초과 | 별도 트랜잭션 `SELECT … FROM member WHERE id = ? FOR UPDATE`를 응답까지 유지 (`FOR UPDATE NOWAIT` 실패로 잠금 확인) | 409 `MEMBER_BUSY` 3/3 | 3.040 / 3.057초 |
| 회원 단위 폐기 기록 실패 | member만 Redis ACL 계정으로 접속, POST 동안 `auth:revoked-member:*` 쓰기만 막음 (다른 키 · Gateway는 정상) | 200 `revocationCompleted: false` 3/3 | 0.148 / 0.173초 |
| Gateway 20초 초과 | 별도 트랜잭션 `LOCK TABLE member_withdrawal_record IN SHARE ROW EXCLUSIVE MODE` | 504 `GATEWAY_TIMEOUT` 3/3 | 20.024 / 20.035초 |

- 연결 거부가 503 응답보다 약 0.2초 긴 것은 연결 실패 1회 재시도(0.2초 뒤)와 맞는다 (호출 수는 셀 수 없어 추정)
- 회원 잠금은 비밀번호 재확인 단계의 첫 잠금에서 걸렸다 (3곳 확인 전에 끝남, 비밀번호 실패 횟수 늘지 않음). 잠금을 푼 뒤 같은 회원 재시도 3/3 성공 (0.145초 평균)
- 탈퇴 확인 실패 3종은 장애를 걷은 뒤 같은 회원 재시도 9/9 성공

### 장애별 회원 · 토큰 상태

| 장애 | 응답 직후 회원 | 탈퇴 기록 · `MemberWithdrawn` | 회원 단위 폐기 기록 · Redis 키 | 기존 Access |
|---|---|---|---|---|
| 탈퇴 확인 실패 · 5초 지연 | `ACTIVE` | 없음 · 없음 | 없음 · 없음 | 200 `ALLOWED` (그대로 쓸 수 있음) |
| 카카오 500 · 시간 초과 (모의) | `ACTIVE`, `provider_id` 유지 | 없음 · 없음 | 없음 · 없음 | 200 `ALLOWED`. 탈퇴 토큰은 소모 |
| `MEMBER_BUSY` | `ACTIVE` | 없음 · 없음 | 없음 · 없음 | 200 `ALLOWED` |
| 폐기 기록 실패 | `DELETED` (커밋됨) | 있음 · 있음 | `PENDING` · 없음 | Gateway 통과 → member 404 `MEMBER_NOT_FOUND` (아래) |
| Gateway 504 | `ACTIVE` (미커밋, 아래) | 없음 · 없음 | 없음 · 없음 | — |

### 회원 단위 폐기 기록 실패 → 재처리

| 시점 | 기존 Access | 판정한 곳 |
|---|---|---|
| 폐기 기록 전 (POST 직후) | Gateway 통과 (Redis에 폐기 시각 없음) | Gateway는 막지 않음 |
| 같은 시점, 확인한 member API (`GET users/me/withdrawal`) | 404 `MEMBER_NOT_FOUND` | member가 탈퇴 회원으로 막음 |
| 장애를 걷은 뒤 첫 재처리에서 `RECORDED` (`attempt_count` 1) | 401 `INVALID_TOKEN` | Gateway |

| 구간 | 3회 값 | 평균 |
|---|---|---|
| POST 응답부터 첫 `401`까지 | 15.7 / 18.0 / 18.6초 | 17.4초 |
| 장애를 해제한 시점(POST 응답 0.7초 뒤)부터 첫 `401`까지 | 15.0 / 17.3 / 18.0초 | 16.8초 |

실패 뒤 다음 시도 +10초와 재처리 주기 10초로 설명되는 범위다 (추정, 재처리 실행 시각은 기록하지 않음).
폐기가 기록되기 전에는 탈퇴한 회원의 기존 Access가 Gateway를 지난다. 회원 상태를 확인하지 않는 다른 서비스 API에서 어떻게 되는지는 확인하지 않았다.

### Gateway 504 뒤 커밋

탈퇴 저장 트랜잭션은 회원 행을 잠근 뒤 `member_withdrawal_record`에 먼저 INSERT하고, 이 쿼리와 트랜잭션에는 시간 제한이 없다. 이 테이블을 잠그자 탈퇴 요청이 그 INSERT에서 기다렸다 (`pg_blocking_pids`로 확인, 대기 `Lock:relation`, 대기 세션 최대 1).

| 시점 | 회원 | 탈퇴 기록 | 회원 단위 폐기 기록 · Redis 키 | `MemberWithdrawn` |
|---|---|---|---|---|
| 504 직후 (잠금 유지) | `ACTIVE`, 인증 버전 그대로 — **미커밋** | 없음 | 없음 · 없음 | 없음 |
| 잠금을 푼 뒤 (약 0.2초 안) | `DELETED`, 인증 버전 +1 — **최초 요청이 커밋됨** | +1 | `RECORDED` · 있음 | 1 |

- 클라이언트는 504(결과 확인 불가)를 받았지만 member는 같은 요청을 끝까지 처리했고, member 쪽 기록은 200 `SUCCESS`다. 응답과 실제 커밋 결과가 다를 수 있다
- 자동 재요청 없음: member가 처리한 탈퇴 POST는 회차마다 1건(`http_server_requests_seconds_count` 증가분), 대기 세션은 최대 1, Gateway 504 로그는 회차마다 1건, Gateway 라우트에 `Retry` 필터 없음
- 잠금이 풀린 뒤 기존 Access는 401 `INVALID_TOKEN`

## 측정하지 않은 것

| 항목 | 이유 · 남은 일 |
|---|---|
| 구간별 시간 (3곳 병렬 확인 · 카카오 연결 해제 · 최종 DB · Redis 처리) | 탈퇴 구간 계측이 없다 (`KakaoAdminClient`는 지표에도 잡히지 않음). 계측 추가 여부는 따로 정함 |
| 실제 카카오 API | 호출하지 않음. 실제 카카오 연결 해제 시간 · 오류 형식은 이 기록에 없다 |
| 그 밖의 장애 | market · payment 단독 장애, 여러 곳 동시 장애, 카카오 연결 실패 · `-101`, 재처리까지 실패하는 긴 Redis 장애, 카카오 회원의 504 |
| 운영 환경 | 로컬 한 기계라 절대값은 운영과 다르다 |

## 기록상 주의

- Gateway 504 원본 CSV의 `member_active_posts_mid_wait` 열(전부 0)은 **무효**다. 측정 스크립트가 `http_server_requests_active_seconds_active_count`를 찾았지만 실제 지표 이름은 `http_server_requests_active_seconds_gcount`였다. 판단에 쓰지 않았고, 처리 중 요청 수 대신 끝난 요청 수와 대기 세션 수를 근거로 했다
- 원본 CSV · 측정 스크립트는 이 저장소에 넣지 않았다. 결과 · 로그에 토큰 · 비밀번호 · 키 값이 없는 것을 확인했다
- 이 문서의 표본 수 · 평균 · 중앙값 · 최댓값은 회차별 원본 CSV 7개(측정 회차만, 워밍업 제외. 시각 추적용 trace CSV 2개는 쓰지 않음)에서 다시 계산해 일치하는 것을 확인했다

## 다시 재기

| 범위 | 내용 |
|---|---|
| 측정 때 실행한 것 | 아래 인프라 · 키 · 기동 · 장애 주입 명령은 2026-11-26 측정 스크립트(저장소 밖)에서 옮겼다. 키 · 비밀번호 값은 자리표시자로 바꿨다 |
| 스크립트 없이 설명만 남은 것 | settlement 장애 주입(WireMock 프록시 · 매핑). 원본 기록의 설명으로 적었고, 명령 그대로는 남아 있지 않다 |
| 문서 정리 때 다시 실행한 것 | Redis ACL 패턴 목록 생성(문자열만 만들고 Redis에 보내지 않음). 컨테이너 · 서비스 · 측정은 다시 실행하지 않았다 |
| 레포와 대조만 한 것 | Kafka 환경변수(`docker-compose.yml`의 `kafka`), 환경변수 이름(각 서비스 `src/main/resources/application.yml`), jar 경로, member readiness(`common/common-logging/src/main/resources/common-logging.yml`의 `probes.enabled`) |

### 인프라

```bash
# 측정 전용 컨테이너 (개발용 5432 · 6379 · 9092와 분리, 레포 루트에서 실행). POSTGRES_PASSWORD는 docker-compose.yml의 로컬 값
docker run -d --name h531-postgres -p 127.0.0.1:15432:5432 -e POSTGRES_DB=justcommit -e POSTGRES_USER=justcommit \
  -e POSTGRES_PASSWORD=<로컬 값> --tmpfs /var/lib/postgresql/data -v "$PWD/docker/postgres:/docker-entrypoint-initdb.d:ro" pgvector/pgvector:pg17
docker run -d --name h531-redis -p 127.0.0.1:16379:6379 redis:8-alpine
# Kafka: docker-compose.yml의 kafka 환경변수에서 호스트 이름과 리스너 포트만 바꿈(호스트 19092 · 브로커 간 29092), 볼륨 · CLUSTER_ID 없이 매번 새로
docker run -d --name h531-kafka --hostname h531-kafka -p 127.0.0.1:19092:19092 \
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT_HOST://localhost:19092,PLAINTEXT://h531-kafka:29092 \
  -e KAFKA_LISTENERS=CONTROLLER://:29093,PLAINTEXT_HOST://:19092,PLAINTEXT://:29092 \
  -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@h531-kafka:29093 -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 -e KAFKA_NUM_PARTITIONS=3 apache/kafka:4.2.1

# 준비 확인: 서비스 DB 7개 생성 · Redis 응답 · Kafka 토픽 목록 조회
docker exec h531-postgres psql -U justcommit -d postgres -Atc "select count(*) from pg_database where datname like '%\_db'"   # 7
docker exec h531-redis redis-cli ping
docker exec h531-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:19092 --list
```

### 키 · 환경변수

키는 측정마다 새로 만들고 권한 600 파일에만 둔다 (값은 어디에도 적지 않음).

| 환경변수 | 만드는 방법 |
|---|---|
| `JWT_PRIVATE_KEY` · `JWT_PUBLIC_KEY` | `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048` · `openssl pkey -pubout`, PEM에서 `-----` 줄을 뺀 한 줄 |
| `ACCOUNT_ENCRYPTION_KEY` · `MEMBER_HMAC_KEY` · `MEMBER_ADDRESS_EVENT_KEY` · `OAUTH2_STATE_SECRET` · `PAYMENT_KEY_ENCRYPTION_KEY` · `CASH_OUT_ACCOUNT_ENCRYPTION_KEY` · `INTERNAL_API_KEY` | 각각 `openssl rand -base64 32` |
| `KAKAO_CLIENT_ID` · `KAKAO_CLIENT_SECRET` · `KAKAO_ADMIN_KEY` | 기동용 임의 값 (실제 카카오 값 아님) |
| 공통 | `REDIS_HOST=localhost` · `REDIS_PORT=16379` · `KAFKA_BOOTSTRAP_SERVERS=localhost:19092` · `TZ=Asia/Seoul` · `MANAGEMENT_HEALTH_MAIL_ENABLED=false` |
| 서비스 주소 (공통) | `MEMBER_SERVICE_URL` 18081 · `PRODUCT_SERVICE_URL` 18082 · `MARKET_SERVICE_URL` 18083 · `PAYMENT_SERVICE_URL` 18084 · `SETTLEMENT_SERVICE_URL` 18085 · `NOTIFICATION_SERVICE_URL` 18086 · `AI_SERVICE_URL` 18087 (모두 `http://localhost:<포트>`), `COURIER_BASE_URL=http://localhost:18300`(닫힌 포트) |
| 서비스마다 | `SERVER_PORT`, gateway 밖의 4개는 `DB_URL=jdbc:postgresql://localhost:15432/{서비스}_db` |

### 서비스 기동

```bash
./gradlew :gateway:bootJar :services:member:bootJar :services:market:bootJar :services:payment:bootJar :services:settlement:bootJar

# 기본 순서: member → market → payment → settlement → gateway (공통 환경변수를 export한 셸에서, JDK 25)
SERVER_PORT=18081 DB_URL=jdbc:postgresql://localhost:15432/member_db     java -Xmx512m -jar services/member/build/libs/member.jar &
SERVER_PORT=18083 DB_URL=jdbc:postgresql://localhost:15432/market_db     java -Xmx512m -jar services/market/build/libs/market.jar &
SERVER_PORT=18084 DB_URL=jdbc:postgresql://localhost:15432/payment_db    java -Xmx512m -jar services/payment/build/libs/payment.jar &
SERVER_PORT=18085 DB_URL=jdbc:postgresql://localhost:15432/settlement_db java -Xmx512m -jar services/settlement/build/libs/settlement.jar &
SERVER_PORT=18080                                                         java -Xmx512m -jar gateway/build/libs/gateway.jar &

curl -s localhost:18081/actuator/health/readiness                        # member UP
for p in 18080 18083 18084 18085; do curl -s localhost:$p/actuator/health; echo; done
```

member만 설정을 바꾸는 측정(모의 카카오 · Redis ACL)은 market → payment → settlement → gateway를 먼저 띄우고 member를 아래 설정으로 마지막에 띄웠다.

### 회차

```bash
# 회차마다: 측정 DB에 새 회원 INSERT → 로그인 → GET users/me/withdrawal → (장애 주입) → POST users/me/withdrawal
# 응답 본문만 회차별 파일로 저장 (-D · -v를 쓰지 않아 헤더 · 쿠키 · 요청은 남지 않음). 출력은 HTTP 상태와 time_total
mkdir -p results/withdrawal && chmod 700 results/withdrawal
RUN=01
curl -s -o "results/withdrawal/post-$RUN.json" -w '%{http_code} %{time_total}\n' -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' --data-binary @withdraw.json http://127.0.0.1:18080/api/v1/users/me/withdrawal

# 저장한 본문에서 오류 코드와 revocationCompleted 확인 (성공이면 code는 -, 실패면 revocationCompleted는 -)
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); print(b["status"], b.get("code") or "-", (b.get("data") or {}).get("revocationCompleted", "-"))' \
  "results/withdrawal/post-$RUN.json"
# 예: 200 - True · 200 - False · 503 WITHDRAWAL_CHECK_UNAVAILABLE - · 504 GATEWAY_TIMEOUT -
```

- 요청 본문 `withdraw.json`(`{ "password": … }`)은 결과 폴더 밖에 권한 600으로 두고 회차가 끝나면 지운다
- `$ACCESS`는 셸 변수로만 쓰고 파일 · 결과 CSV에 적지 않는다. 응답 본문에는 토큰이 없다 (성공 응답의 Refresh 쿠키 만료는 헤더라 저장되지 않음)

### 장애 주입

| 장애 | 주입 |
|---|---|
| 탈퇴 확인 한 곳 (스크립트 없음) | 아래 「settlement WireMock 매핑」. member만 `SETTLEMENT_SERVICE_URL=http://localhost:18089`. 연결 거부는 `docker stop h531-wiremock` |
| `MEMBER_BUSY` | `BEGIN; SELECT id FROM member WHERE id = <회원> FOR UPDATE;` 를 연 채 POST, 응답 뒤 `ROLLBACK` |
| 폐기 기록 실패 | 아래 Redis ACL. member만 `SPRING_DATA_REDIS_USERNAME=h531member` · `REDIS_PASSWORD=<계정 비밀번호>`로 띄움 (Gateway · 다른 서비스는 기본 계정) |
| Gateway 504 | `BEGIN; LOCK TABLE member_withdrawal_record IN SHARE ROW EXCLUSIVE MODE;` 를 연 채 POST, 504 뒤 `ROLLBACK` |
| 모의 카카오 | `wiremock/wiremock:3.13.1` 컨테이너 `h531-kakao`(`127.0.0.1:18090`) · `axllent/mailpit:v1.24.1` 컨테이너 `h531-mailpit`(SMTP `127.0.0.1:11025`). member `SPRING_APPLICATION_JSON` = `{"member":{"kakao":{"api-base-url":"http://localhost:18090"}},"spring":{"mail":{"host":"localhost","port":11025,"properties":{"mail.smtp.auth":"false","mail.smtp.starttls.enable":"false","mail.smtp.starttls.required":"false"}}}}`. 메일 세 값은 `spring.mail.properties.mail.smtp.auth` · `spring.mail.properties.mail.smtp.starttls.enable` · `spring.mail.properties.mail.smtp.starttls.required`로 적용된다 (member `application.yml`의 기본값은 모두 `true`) |

settlement WireMock 매핑 (측정 때 스크립트가 남지 않아 원본 기록의 설명과 WireMock 매핑 형식으로 다시 쓴 예시):

| 매핑 | 우선순위 | 응답 | 쓰는 때 |
|---|---|---|---|
| 기본 프록시 | 10 | 모든 요청을 실제 settlement로 전달 | 항상 |
| 지연 (`fixedDelayMilliseconds` 5000 · 2000) | 1 | `withdrawalCheck`만 실제 settlement로 전달하되 지연 뒤 응답 | 지연 회차의 POST 직전 추가, 직후 삭제 |
| 503 | 1 | `withdrawalCheck`만 실제 settlement로 보내지 않고 503 | 503 회차의 POST 직전 추가, 직후 삭제 |

```bash
# WireMock (컨테이너 안에서 호스트의 settlement 18085를 host.docker.internal로 부름)
docker run -d --name h531-wiremock --add-host=host.docker.internal:host-gateway -p 127.0.0.1:18089:8080 \
  wiremock/wiremock:3.13.1 --disable-banner

# 기본 프록시 (한 번)
curl -s -X POST http://localhost:18089/__admin/mappings -H 'Content-Type: application/json' --data-binary '{
  "id": "00000000-0000-0000-0000-00000000c000",
  "priority": 10,
  "request": { "method": "ANY", "urlPattern": ".*" },
  "response": { "proxyBaseUrl": "http://host.docker.internal:18085" }
}'

# 지연: 실제 settlement로 전달 + 지연을 같은 매핑에 (5초 회차는 5000, 2초 회차는 2000)
curl -s -X POST http://localhost:18089/__admin/mappings -H 'Content-Type: application/json' --data-binary '{
  "id": "00000000-0000-0000-0000-00000000c001",
  "priority": 1,
  "request": { "method": "GET", "urlPathPattern": "/internal/v1/settlements/members/[0-9]+/withdrawalCheck" },
  "response": { "proxyBaseUrl": "http://host.docker.internal:18085", "fixedDelayMilliseconds": 5000 }
}'

# 503: 전달하지 않고 바로 503
curl -s -X POST http://localhost:18089/__admin/mappings -H 'Content-Type: application/json' --data-binary '{
  "id": "00000000-0000-0000-0000-00000000c002",
  "priority": 1,
  "request": { "method": "GET", "urlPathPattern": "/internal/v1/settlements/members/[0-9]+/withdrawalCheck" },
  "response": { "status": 503 }
}'

# POST 직후 회차 매핑 삭제 (기본 프록시는 남김)
curl -s -X DELETE http://localhost:18089/__admin/mappings/00000000-0000-0000-0000-00000000c001   # 503 회차는 ...c002
```

Redis ACL (`redis-cli`로 `h531-redis`에 실행):

```bash
# 1. member 전용 계정 (모든 키 · 명령 허용). 비밀번호는 표준 입력으로 넘겨 프로세스 인자에 남기지 않음
printf 'ACL SETUSER h531member reset on >%s ~* &* +@all\n' "<계정 비밀번호>" | docker exec -i h531-redis redis-cli

# 2. 장애: auth:revoked-member:* 는 읽기만, 나머지 키는 읽기 · 쓰기.
#    Redis ACL에 제외 패턴이 없어 접두사(20글자)의 글자 위치마다 "여기까지 같고 다음 글자가 다른" 패턴을 나열한다.
#    읽기 · 쓰기를 함께 쓰는 명령(EVAL 등)은 한 패턴이 두 권한을 모두 줘야 해서 %W~ 대신 ~ 를 쓴다
docker exec h531-redis redis-cli ACL SETUSER h531member resetkeys '%R~auth:revoked-member:*' \
  '~[^a]*' '~a[^u]*' '~au[^t]*' '~aut[^h]*' '~auth[^:]*' '~auth:[^r]*' '~auth:r[^e]*' '~auth:re[^v]*' \
  '~auth:rev[^o]*' '~auth:revo[^k]*' '~auth:revok[^e]*' '~auth:revoke[^d]*' '~auth:revoked[^-]*' \
  '~auth:revoked-[^m]*' '~auth:revoked-m[^e]*' '~auth:revoked-me[^m]*' '~auth:revoked-mem[^b]*' \
  '~auth:revoked-memb[^e]*' '~auth:revoked-membe[^r]*' '~auth:revoked-member[^:]*'

# 3. 해제
docker exec h531-redis redis-cli ACL SETUSER h531member resetkeys '~*'
```

정리: 서비스 JVM 종료, `docker rm -f h531-postgres h531-redis h531-kafka`(쓴 경우 `h531-wiremock` · `h531-kakao` · `h531-mailpit`도).
