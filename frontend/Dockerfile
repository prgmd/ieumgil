# 이음길 프론트엔드 — 정적 번들을 nginx 로 서빙하는 2단 빌드
#
# ⚠️ VITE_* 는 런타임 환경변수가 아니다. Vite 가 빌드할 때 번들 소스에 문자열로
#    치환해 넣는다. 그래서 컨테이너에 environment 로 넣어도 아무 일도 일어나지
#    않고, 값이 바뀌면 **이미지를 다시 빌드**해야 한다. 여기서 ARG 로 받는 이유다.
#
# 값은 docker-compose.yml 의 build.args 를 통해 들어온다. 개발용 .env 를 그대로
# 쓰되, 배포와 값이 다른 VITE_KAKAO_REDIRECT_URI 만 셸에서 덮어쓴다
# (셸 환경변수가 .env 보다 우선한다):
#
#   VITE_KAKAO_REDIRECT_URI=https://<도메인>/oauth/kakao/callback \
#     FRONTEND_IMAGE_REPO=<계정>/ieumgil-frontend IMAGE_TAG=$(git rev-parse --short HEAD) \
#     docker compose build

# ---------- build ----------
FROM node:22-alpine AS build
WORKDIR /app

ARG VITE_API_BASE_URL=/api
ARG VITE_KAKAO_REST_API_KEY
ARG VITE_KAKAO_REDIRECT_URI
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_KAKAO_REST_API_KEY=$VITE_KAKAO_REST_API_KEY \
    VITE_KAKAO_REDIRECT_URI=$VITE_KAKAO_REDIRECT_URI

# 의존성만 먼저 — 소스만 바뀐 빌드에서는 이 레이어를 재사용한다.
# npm ci 는 package-lock.json 을 그대로 재현한다(install 과 달리 락을 갱신하지 않는다)
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# 잘못된 값은 조용히 번들에 박혀 브라우저에서만 드러난다 — 빌드 단계에서 끊는다.
# 특히 localhost 검사가 중요하다: 개발용 .env 를 그대로 쓰면 Redirect URI 가
# localhost 인 채로 구워지고, 배포 후 카카오 로그인이 전부 실패한다.
# (VITE_API_BASE_URL 은 기본값 /api 가 있어 검사하지 않는다)
RUN set -e; \
    if [ -z "$VITE_KAKAO_REST_API_KEY" ]; then \
      echo "ERROR: VITE_KAKAO_REST_API_KEY 가 비어 있습니다"; exit 1; \
    fi; \
    case "$VITE_KAKAO_REDIRECT_URI" in \
      "") echo "ERROR: VITE_KAKAO_REDIRECT_URI 가 비어 있습니다"; exit 1 ;; \
      *localhost*|*127.0.0.1*) \
        echo "ERROR: VITE_KAKAO_REDIRECT_URI 가 개발용입니다 ($VITE_KAKAO_REDIRECT_URI)"; \
        echo "       배포 이미지에는 실제 도메인이 들어가야 합니다. 빌드할 때 덮어쓰세요:"; \
        echo "       VITE_KAKAO_REDIRECT_URI=https://<도메인>/oauth/kakao/callback docker compose build"; \
        exit 1 ;; \
    esac; \
    npm run build

# ---------- runtime ----------
FROM nginx:1.27-alpine

# SPA 라우팅·캐시 정책은 이 이미지가 들고 다닌다 — 호스트 nginx 는 프록시만 한다
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
