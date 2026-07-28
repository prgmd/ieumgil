FROM postgres:16-alpine

# 로그/타임스탬프를 KST로 맞춘다
ENV TZ=Asia/Seoul \
    PGTZ=Asia/Seoul

RUN apk add --no-cache tzdata

# 최초 기동(데이터 디렉터리가 비어 있을 때) 1회만 실행되는 초기화 스크립트
COPY initdb/ /docker-entrypoint-initdb.d/
