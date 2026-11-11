# 서비스 공통 Dockerfile — 빌드할 모듈 폴더를 MODULE로 받는다
#   docker build --build-arg MODULE=services/member -t replant-member .
#   docker build --build-arg MODULE=gateway -t replant-gateway .

# 1단계: 빌드 (JDK 이미지에서 그 모듈의 실행 jar 하나만 만든다)
FROM eclipse-temurin:25-jdk AS build
ARG MODULE
WORKDIR /app

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
# settings.gradle이 모든 모듈을 include하므로 폴더가 다 있어야 Gradle이 구조를 읽는다
COPY common common
COPY gateway gateway
COPY services services

# MODULE이 없으면 바로 멈춘다. services/member → Gradle 경로 :services:member
# Gradle 내려받기 · 라이브러리는 캐시(--mount=type=cache)에 남겨 다음 빌드에서 재사용한다
# 테스트는 CI(PR마다 ./gradlew build)에서 돌기 때문에 이미지 빌드에서는 건너뛴다
RUN --mount=type=cache,target=/root/.gradle \
    test -n "$MODULE" || { echo "MODULE 빌드 인자가 필요합니다 (예: services/member)"; exit 1; } \
 && sed -i 's/\r$//' gradlew && chmod +x gradlew \
 && ./gradlew --no-daemon ":$(echo "$MODULE" | tr / :):bootJar" -x test \
 && cp "$(ls "$MODULE"/build/libs/*.jar | grep -v plain)" app.jar

# 2단계: 실행 (JRE만 있는 작은 이미지에 jar만 옮긴다)
FROM eclipse-temurin:25-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /app/app.jar app.jar
USER app

# 컨테이너 안에서는 서비스가 모두 8080 (docs/reference/ports.md)
EXPOSE 8080
# 메모리는 컨테이너 상한의 70%까지(docs/infra/deploy.md), 시간대는 한국
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Duser.timezone=Asia/Seoul"
ENTRYPOINT ["java", "-jar", "app.jar"]
