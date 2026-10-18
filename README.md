# JustCommit Backend

Spring Modulith 기반의 단일 Spring Boot 백엔드 프로젝트입니다. 현재는 공통 인프라와 모듈 경계만 준비하며, 비즈니스 기능은 포함하지 않습니다.

## Module structure

`market` is the single module for the marketplace transaction lifecycle. Its internal packages
separate cart, checkout, order, shipment, and refund responsibilities without introducing a
cross-module transaction boundary. See [module architecture](docs/module-architecture.md) for the
ownership and dependency rules.

## Prerequisites

- Java 25 (Gradle toolchain이 없으면 Temurin JDK를 자동으로 내려받습니다.)
- Docker Desktop 또는 Docker Engine + Docker Compose

## Local infrastructure

```bash
docker compose up -d
```

| Service | Host | Port | Default credentials |
| --- | --- | --- | --- |
| PostgreSQL | `localhost` | `5432` | database/user/password: `justcommit` / `justcommit` / `justcommit` |
| Redis | `localhost` | `6379` | none |
| Kafka | `localhost` | `9092` | plaintext local broker |
| S3 (RustFS) | `localhost` | `9000` (API), `9001` (웹 콘솔) | access/secret key: `replantlocal` / `replantlocal123` (로컬 전용) |

로컬 S3는 운영 S3 대신 쓰는 S3 호환 저장소(RustFS)입니다. `rustfs-init`이 처음 한 번 운영과 같은 버킷(`team06-replant-images`), 공개 읽기 정책(`products/*`, `species/*`), CORS를 만들고 종료합니다. 설정 파일은 `docker/s3/`에 있습니다. AWS SDK에서는 endpoint를 `http://localhost:9000`으로, path-style 접근을 켜서 사용합니다. 업로드한 파일은 `http://localhost:9000/team06-replant-images/{key}`로 조회되고, 웹 콘솔(`http://localhost:9001`)에서 확인할 수 있습니다.

## Production deploy (EC2)

운영 서버는 Nginx(HTTPS, `replant.kro.kr`)가 요청을 받아 같은 서버의 Spring 컨테이너(`127.0.0.1:8080`)로 넘깁니다. `docker-compose.prod.yml`에 Spring(app), PostgreSQL, Redis, Kafka가 있고, 모든 포트는 `127.0.0.1`에만 열립니다.

전체 배포(백엔드 develop, 프론트 develop, 모의 택배사 main)는 `scripts/deploy.sh` 하나로 합니다. 서버 홈에 복사해 `bash ~/deploy.sh`로 실행하며, 비밀값 원본은 서버의 `~/secrets/replant.env`에 둡니다. 배포 전에 `.env.example`에 있는 변수가 서버에 모두 있는지 검사하고, 끝나면 health 확인과 품종 데이터 적재까지 합니다.

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

PostgreSQL data is retained in the `postgres-data` Docker volume. Stop services with `docker compose down`; use `docker compose down -v` only when intentionally removing local data.

## Run and test

```bash
./gradlew test
./gradlew bootRun --args='--spring.profiles.active=local'
```

`local` is the default profile. `test` also targets PostgreSQL and is configured for `create-drop`; future PostgreSQL Testcontainers tests can override its datasource properties. `prod` requires database, Redis, and Kafka environment variables.

Run only the Modulith architecture check with:

```bash
./gradlew test --tests com.justcommit.backend.ModulithArchitectureTest
```

## Environment variables

- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` (prod only)
- `KAFKA_BOOTSTRAP_SERVERS`
- `AWS_REGION`, `S3_BUCKET_NAME`, `S3_PUBLIC_BASE_URL` (prod는 EC2 IAM 역할로 인증하므로 AWS 키를 두지 않습니다)
- `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` (local only, 로컬 RustFS 접속용)
- `SPRING_APPLICATION_NAME` (optional)

Local defaults match `docker-compose.yml`. Production does not include database credentials in source configuration.

## Architecture and infrastructure TODOs

See [module architecture](docs/module-architecture.md) for module responsibilities and [infrastructure TODOs](docs/infrastructure-todo.md) for Redis key namespaces, Kafka event candidates, and the rule that future asynchronous events use Kafka rather than a parallel Spring Event implementation.

## 데이터 출처
- 품종 정보 및 사진: 농촌진흥청 농사로 (https://www.nongsaro.go.kr)