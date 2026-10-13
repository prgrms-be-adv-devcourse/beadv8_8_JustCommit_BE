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

운영 서버(EC2)에서는 `docker-compose.prod.yml`을 사용합니다. `.env.example`을 `.env`로 복사해 값을 채운 뒤 `docker compose -f docker-compose.prod.yml up -d`로 실행합니다. 운영 Redis는 비밀번호, 메모리 제한(`noeviction`), `127.0.0.1` 바인딩이 적용되어 로컬 설정과 다릅니다.

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
- `SPRING_APPLICATION_NAME` (optional)

Local defaults match `docker-compose.yml`. Production does not include database credentials in source configuration.

## Architecture and infrastructure TODOs

See [module architecture](docs/module-architecture.md) for module responsibilities and [infrastructure TODOs](docs/infrastructure-todo.md) for Redis key namespaces, Kafka event candidates, and the rule that future asynchronous events use Kafka rather than a parallel Spring Event implementation.
