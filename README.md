# <img align="absmiddle" width="50" height="50" alt="replant 로고" src="https://github.com/user-attachments/assets/dfb1b373-1442-4e0b-879a-317d405e04bd" /> replant
> 직접 키운 식물을 사고파는 C2C 식물 마켓
> 
> #### 🪴 [replant.kro.kr](https://replant.kro.kr)

<!-- ## 서비스 화면 -->

## 주요 기능

| 구분 | 주요 기능 |
| --- | --- |
| **회원 (Member)** | 이메일 인증 회원가입, JWT 로그인, 카카오 로그인, 배송지 관리, 판매자 등록 |
| **상품 (Product)** | 상품 등록 · 수정 · 삭제 · 숨김, 판매 금지 품종 등록 차단, 품종 · 생육환경 · 가격 필터 검색, 품종 정보 조회(농사로 217종) |
| **장바구니 · 주문 (Market)** | 장바구니 담기 · 삭제 · 조회, 장바구니 주문, 수거 전 주문 취소, 구매 확정 · 배송 완료 3일 후 자동 확정, 주문 · 판매 내역 조회 |
| **결제 (Payment)** | 토스페이먼츠 예치금 충전, 예치금 결제, 주문 취소 시 상품 금액 · 배송비 환불, 잔액 · 거래 내역 조회 |
| **정산 (Settlement)** | 구매 확정 시 수수료 공제 후 판매자 정산, 정산 내역 조회 |
| **배송 (Shipment)** | 결제 시 택배 자동 접수, 수거 → 배송 중 → 배송 완료 상태 조회 |
| **알림 (Notification)** | 주문 · 배송 · 취소 · 구매 확정 알림, 실시간 미읽음 알림, 읽음 처리 |


## 기술 스택
| 분야 | 기술 |
|---|---|
| **Backend** | ![Java](https://img.shields.io/badge/Java_25-007396?style=flat-square&logo=openjdk&logoColor=white) ![Spring Boot](https://img.shields.io/badge/Spring_Boot_4.1-6DB33F?style=flat-square&logo=springboot&logoColor=white) ![Spring Modulith](https://img.shields.io/badge/Spring_Modulith-6DB33F?style=flat-square&logo=spring&logoColor=white) ![Spring Security](https://img.shields.io/badge/Spring_Security-6DB33F?style=flat-square&logo=springsecurity&logoColor=white) ![JWT](https://img.shields.io/badge/JWT-000000?style=flat-square&logo=jsonwebtokens&logoColor=white) ![Spring Data JPA](https://img.shields.io/badge/Spring_Data_JPA-6DB33F?style=flat-square&logo=spring&logoColor=white) |
| **Data · Messaging** | ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?style=flat-square&logo=postgresql&logoColor=white) ![Redis](https://img.shields.io/badge/Redis-DC382D?style=flat-square&logo=redis&logoColor=white) ![Kafka](https://img.shields.io/badge/Kafka-231F20?style=flat-square&logo=apachekafka&logoColor=white) |
| **Infra · CI/CD** | ![AWS EC2](https://img.shields.io/badge/AWS_EC2-FF9900?style=flat-square&logo=amazonec2&logoColor=white) ![AWS S3](https://img.shields.io/badge/AWS_S3-569A31?style=flat-square&logo=amazons3&logoColor=white) ![Docker Compose](https://img.shields.io/badge/Docker_Compose-2496ED?style=flat-square&logo=docker&logoColor=white) ![Nginx](https://img.shields.io/badge/Nginx-009639?style=flat-square&logo=nginx&logoColor=white) ![Let's Encrypt](https://img.shields.io/badge/Let's_Encrypt-003A70?style=flat-square&logo=letsencrypt&logoColor=white) ![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?style=flat-square&logo=githubactions&logoColor=white) |
| **Frontend** | ![React](https://img.shields.io/badge/React-20232A?style=flat-square&logo=react&logoColor=61DAFB) ![Vite](https://img.shields.io/badge/Vite-646CFF?style=flat-square&logo=vite&logoColor=white) ![axios](https://img.shields.io/badge/axios-5A29E4?style=flat-square&logo=axios&logoColor=white) ![CSS Modules](https://img.shields.io/badge/CSS_Modules-000000?style=flat-square&logo=cssmodules&logoColor=white) |
| **External API** | ![Toss Payments](https://img.shields.io/badge/토스페이먼츠-0064FF?style=flat-square&logo=toss&logoColor=white) ![Kakao Login](https://img.shields.io/badge/카카오_로그인-FFCD00?style=flat-square&logo=kakaotalk&logoColor=black) ![Gmail SMTP](https://img.shields.io/badge/Gmail_SMTP-EA4335?style=flat-square&logo=gmail&logoColor=white) ![Nongsaro](https://img.shields.io/badge/농사로_Open_API-4CAF50?style=flat-square) |
| **Tools** | ![IntelliJ IDEA](https://img.shields.io/badge/IntelliJ_IDEA-000000?style=flat-square&logo=intellijidea&logoColor=white) ![GitHub](https://img.shields.io/badge/GitHub-181717?style=flat-square&logo=github&logoColor=white) ![Swagger](https://img.shields.io/badge/Swagger-85EA2D?style=flat-square&logo=swagger&logoColor=black) ![Figma](https://img.shields.io/badge/Figma-F24E1E?style=flat-square&logo=figma&logoColor=white) ![Notion](https://img.shields.io/badge/Notion-000000?style=flat-square&logo=notion&logoColor=white) |

## 시스템 아키텍처
<p align="center">
  <img width="650" alt="replant 시스템 아키텍처" src="https://github.com/user-attachments/assets/eda82d1e-3bba-407b-9829-872196c4cc35" />
</p>


## ERD
<p align="center">
  <img alt="replant ERD" src="https://github.com/user-attachments/assets/d4952fcb-5501-478d-9a44-935d1984c28a" />
</p>


## 모듈 구조

Spring Modulith 기반의 모듈형 모놀리식 아키텍처를 적용하여 도메인별 책임과 모듈 간 의존성을 분리했습니다. 모듈끼리는 공개 API(UseCase · Query · DTO)와 Kafka 이벤트로 통신하고, 다른 모듈의 Entity나 Repository는 직접 참조하지 않습니다.

```text
com.justcommit.backend
├── member          # 회원 관리
├── product         # 상품 관리
├── market          # 거래 관리
│   ├── cart        # 장바구니
│   ├── order       # 주문 관리
│   ├── shipment    # 배송 관리
│   └── refund      # 환불 관리 (최종 프로젝트 예정)
├── payment         # 결제 및 예치금
├── settlement      # 판매 대금 정산
└── notification    # 알림
```

자세한 모듈별 책임과 의존성 규칙은 [모듈 아키텍처 문서](docs/module-architecture.md)를 참고하세요.


## 팀 구성 및 담당

| 김윤관(PO) | 김환희 | 박성수 | 박지원 | 장세미 |
|:---:|:---:|:---:|:---:|:---:|
| <img src="https://github.com/gasic37200.png" width="100" height="100"> | <img src="https://github.com/rlaksl.png" width="100" height="100"> | <img src="https://github.com/projectsy1105-source.png" width="100" height="100"> | <img src="https://github.com/jjuny-won.png" width="100" height="100"> | <img src="https://github.com/ssem-i.png" width="100" height="100"> |
| Shipment<br>Notification<br>AWS | Member | Market | Product | Payment<br>Settlement |
| [GitHub](https://github.com/gasic37200) | [GitHub](https://github.com/rlaksl) | [GitHub](https://github.com/projectsy1105-source) | [GitHub](https://github.com/jjuny-won) | [GitHub](https://github.com/ssem-i) |

## 문서
- [Figma 기획 자료](https://www.figma.com/board/rnZsalDwY3qwQA0zXaRz6J/일단커밋--기획-자료?node-id=953-5785)
- [세미 프로젝트 발표 자료](https://docs.google.com/presentation/d/1Q5_6gDUDwS8OuLEVtGVHGAggYA9iuS1z/edit)
- [모듈 아키텍처](docs/module-architecture.md)
- [인프라 TODO](docs/infrastructure-todo.md)


<img src="https://raw.githubusercontent.com/andreasbm/readme/master/assets/lines/grass.png" width="100%">

## 로컬 실행 방법
### 사전 준비

- Java 25 (Gradle toolchain이 없으면 Temurin JDK를 자동으로 내려받습니다.)
- Docker Desktop 또는 Docker Engine + Docker Compose

### 로컬 인프라 실행

```bash
docker compose up -d
```

| 서비스 | 호스트 | 포트 | 기본 접속 정보 |
| --- | --- | --- | --- |
| PostgreSQL | `localhost` | `5432` | database/user/password: `justcommit` / `justcommit` / `justcommit` |
| Redis | `localhost` | `6379` | 없음 |
| Kafka | `localhost` | `9092` | 암호화되지 않은 로컬 브로커 |
| S3 (RustFS) | `localhost` | `9000` (API), `9001` (웹 콘솔) | access/secret key: `replantlocal` / `replantlocal123` (로컬 전용) |

PostgreSQL 데이터는 `postgres-data` Docker 볼륨에 유지됩니다. 서비스는 `docker compose down`으로 종료하고, 로컬 데이터를 지우려는 경우에만 `docker compose down -v`를 사용합니다.

로컬 S3는 운영 S3 대신 쓰는 S3 호환 저장소(RustFS)입니다. `rustfs-init`이 처음 한 번 운영과 같은 버킷(`team06-replant-images`), 공개 읽기 정책(`products/*`, `species/*`), CORS를 만들고 종료합니다. 설정 파일은 `docker/s3/`에 있습니다. AWS SDK에서는 endpoint를 `http://localhost:9000`으로, path-style 접근을 켜서 사용합니다. 업로드한 파일은 `http://localhost:9000/team06-replant-images/{key}`로 조회되고, 웹 콘솔(`http://localhost:9001`)에서 확인할 수 있습니다. 



### 실행 및 테스트

```bash
./gradlew test
./gradlew bootRun --args='--spring.profiles.active=local'
```

기본 프로파일은 `local`입니다.
`test` 프로파일도 PostgreSQL을 사용하며, `create-drop` 방식으로 테이블을 생성하고 삭제하도록 설정되어 있습니다.
`prod` 프로파일을 실행하려면 데이터베이스, Redis, Kafka 관련 환경변수가 필요합니다.

Spring Modulith 아키텍처 검증 테스트만 실행하려면 다음 명령어를 사용합니다.
```bash
./gradlew test --tests com.justcommit.backend.ModulithArchitectureTest
```

### 환경변수

- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` (운영 환경 전용)
- `KAFKA_BOOTSTRAP_SERVERS`
- `AWS_REGION`, `S3_BUCKET_NAME`, `S3_PUBLIC_BASE_URL` (운영 환경에서는 EC2 IAM 역할로 인증하므로 AWS 키를 두지 않습니다)
- `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` (로컬 환경 전용, 로컬 RustFS 접속용)
- `SPRING_APPLICATION_NAME` (선택)

로컬 환경의 기본 설정은 `docker-compose.yml`과 동일합니다. 운영 환경에서는 데이터베이스 접속 정보를 소스 코드 설정 파일에 포함하지 않습니다.


## 운영 환경 배포 (EC2)

<details>
<summary>배포 방법 보기</summary>

운영 서버는 Nginx(HTTPS, `replant.kro.kr`)가 요청을 받아 같은 서버의 Spring 컨테이너(`127.0.0.1:8080`)로 넘깁니다. `docker-compose.prod.yml`에 Spring(app), PostgreSQL, Redis, Kafka가 있고, 모든 포트는 `127.0.0.1`에만 열립니다.

전체 배포(백엔드 develop, 프론트 develop, 모의 택배사 main)는 `scripts/deploy.sh` 하나로 합니다. 서버 홈에 복사해 `bash ~/deploy.sh`로 실행하며, 비밀값은 서버에 별도로 보관합니다. 배포 전에 `.env.example`에 있는 변수가 서버에 모두 있는지 검사하고, 끝나면 health 확인과 품종 데이터 적재까지 합니다.

아래는 백엔드만 직접 띄울 때입니다.

```bash
cp .env.example .env              # 처음 한 번. 값을 채우고 chmod 600 .env
docker network create replant-net # 처음 한 번. 모의 택배사(404-express)와 함께 쓰는 네트워크
docker compose -f docker-compose.prod.yml up -d --build
```

- 프로젝트 이름이 `replant`로 고정되어 있어 어느 폴더에서 실행해도 같은 볼륨(데이터)을 씁니다.
- 세미 프로젝트에서는 Flyway를 끄고 `ddl-auto: update`로 테이블을 만듭니다. 품종 데이터는 첫 배포 후 한 번 넣습니다 (이미 있는 행은 건너뜀).

```bash
docker compose -f docker-compose.prod.yml exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < src/main/resources/db/data/species.sql
```

- 확인: `curl https://replant.kro.kr/actuator/health` 가 `UP`이면 정상입니다.
- `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`이 비어 있으면 Spring이 기동에 실패합니다.
- 운영 Redis는 비밀번호, 메모리 제한(`noeviction`), `127.0.0.1` 바인딩이 적용되어 로컬 설정과 다릅니다.


</details>


## 데이터 출처
- 품종 정보 및 사진: 농촌진흥청 농사로 (https://www.nongsaro.go.kr)

