# 1단계: 빌드 (JDK 포함 이미지에서 실행 가능한 jar를 만든다)
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# 의존성 다운로드를 소스보다 먼저 해서, 소스만 바뀌면 이 층은 캐시를 재사용한다
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
# Windows에서 받은 gradlew가 CRLF여도 리눅스에서 실행되도록 줄바꿈을 정리한다
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

COPY src src
# 테스트는 CI(PR마다 ./gradlew build)에서 이미 돌기 때문에 이미지 빌드에서는 건너뛴다
RUN ./gradlew --no-daemon bootJar -x test \
 && cp "$(ls build/libs/*.jar | grep -v plain)" app.jar

# 2단계: 실행 (JRE만 있는 작은 이미지에 jar만 옮긴다)
FROM eclipse-temurin:25-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /app/app.jar app.jar
USER app

EXPOSE 8080
# 메모리는 컨테이너 상한(compose mem_limit)의 75%까지, 시간대는 한국
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Seoul"
ENTRYPOINT ["java", "-jar", "app.jar"]
