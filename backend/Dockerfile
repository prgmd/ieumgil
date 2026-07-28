# ---------- build ----------
# Gradle wrapper(8.12) + JDK 21 으로 bootJar 생성
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# 빌드 스크립트만 먼저 복사 → 소스만 바뀐 빌드에서는 아래 레이어를 재사용
# BuildKit 캐시 마운트로 Gradle 배포본/의존성 jar를 빌드 간에 재사용한다
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle build.gradle ./
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet

COPY src src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar \
 && cp build/libs/*-SNAPSHOT.jar /workspace/app.jar

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

ENV TZ=Asia/Seoul \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

RUN apk add --no-cache tzdata curl \
 && addgroup -S app && adduser -S app -G app

COPY --from=build --chown=app:app /workspace/app.jar app.jar
USER app

EXPOSE 8080
# exec 로 실행해야 PID 1이 java가 되어 docker stop 시 SIGTERM을 받는다
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
