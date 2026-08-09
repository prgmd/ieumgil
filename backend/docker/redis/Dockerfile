FROM redis:7-alpine

ENV TZ=Asia/Seoul
RUN apk add --no-cache tzdata

COPY redis.conf /usr/local/etc/redis/redis.conf

CMD ["redis-server", "/usr/local/etc/redis/redis.conf"]
