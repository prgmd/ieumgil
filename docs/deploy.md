# 배포 런북 (EC2 + 기존 nginx HTTPS)

프론트·백엔드·DB·Redis 를 **하나의 Compose 스택**으로 올리고, 이미 인증서를 들고
있는 **호스트 nginx** 뒤에 붙이는 절차.

```
브라우저 ──HTTPS──> 호스트 nginx (인증서 보유)
                      ├─ /      → 127.0.0.1:5173   ieumgil-frontend
                      ├─ /api/  → 127.0.0.1:8081   ieumgil-backend
                      └─ /ws    → 127.0.0.1:8081   ieumgil-backend (WebSocket)

                    ┌─ 한 스택(name: ieumgil) ─────────────────┐
                    │ frontend · backend · postgres · redis   │
                    └─────────────────────────────────────────┘
```

| 파일 | 어디서 | 하는 일 |
|---|---|---|
| `docker-compose.prod.yml` | EC2 (`docker-compose.yml` 로 복사) | 이미지 4개를 받아 켠다. `build:` 없음 |
| `.env.prod.example` | EC2 (`.env` 로 복사) | 이미지 태그·DB·키 |
| `frontend/docker-compose.yml` | 소스 있는 곳 | 프론트 이미지 굽기 |
| `backend/docker-compose.yml` | 로컬 개발 | 로컬에서 전부 띄우기(빌드 포함) |

**EC2 에는 저장소를 클론하지 않는다.** 소스가 필요한 것은 이미지를 굽는 쪽뿐이다.

`up -d` 는 바뀐 서비스만 재생성하므로, 한 스택이어도 프론트만 올릴 때 DB 는
건드리지 않는다. 단 **`down` 은 스택 전체를 내린다** — 쓰지 말 것.

> 아래 예시의 도메인은 `i15a107.p.ssafy.io` 다. 실제 도메인으로 바꿔 쓸 것.

> 📋 **서버에 접속해 실제로 배포하는 중이라면 [deploy-checklist.md](./deploy-checklist.md)
> 를 따라갈 것.** 이 문서는 "왜 그렇게 하는지"를 담은 참조용이고, 체크리스트는
> 단계마다 확인 명령이 붙은 실행용이다.

---

## 0. 선행 작업 — 이거 먼저 안 하면 배포해도 안 뜬다

### 0-1. DB 마이그레이션 006 ★

**`DDL_AUTO=update` 여도 안심할 수 없다.** Hibernate 는 컬럼을 만들 뿐
**데이터를 옮기지 않는다.** 값을 채우는 마이그레이션은 손으로 돌려야 한다.

006 은 블록 시간 모델을 `day_no + start_time` → `start_offset_minutes` 로 옮긴다.
안 돌리면 기존 블록의 오프셋이 전부 `NULL` 이 되고, 프론트는 그것을 **후보(POOL)**
로 해석한다 — **모든 일정이 타임라인에서 사라져 후보 목록으로 내려간다.**

```bash
docker exec -i ieumgil-postgres sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB' \
  < backend/docker/postgres/migration/006-block-time-model.sql
```

확인 — 두 수가 같아야 한다:

```bash
docker exec -i ieumgil-postgres sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB -c \
  "SELECT count(*) FILTER (WHERE day_no IS NOT NULL) AS on_day, \
          count(*) FILTER (WHERE start_offset_minutes IS NOT NULL) AS migrated FROM block"'
```

005(마지막 편집자)는 `DDL_AUTO=update` 면 컬럼이 이미 있다. 백필만 없는데 프론트가
작성자로 폴백하므로 화면은 정상이다. 007(옛 컬럼 삭제)은 **비가역**이라 발표 후에 한다.

### 0-2. 카카오 개발자센터 등록 2곳

반영에 시간이 걸릴 수 있으니 가장 먼저 해 둔다.

| 위치 | 값 | 빠뜨리면 |
|---|---|---|
| 카카오 로그인 > Redirect URI | `https://i15a107.p.ssafy.io/oauth/kakao/callback` | 로그인 실패 (KOE006) |
| 플랫폼 > Web > 사이트 도메인 | `https://i15a107.p.ssafy.io` | **지도가 안 뜬다** (JS SDK 도메인 검증) |

두 번째를 놓치기 쉽다. 로그인은 되는데 지도만 회색으로 나오면 이걸 의심할 것.

### 0-3. 기존 볼륨 이름 확인 ★

compose 의 볼륨은 `<프로젝트명>_postgres-data` 라는 이름으로 만들어진다.
새 compose 의 `name:` 이 지금 쓰는 것과 다르면 **빈 DB 가 새로 생긴다** —
기존 데이터는 옛 볼륨에 그대로 남지만 앱에서는 안 보인다.

```bash
docker volume ls | grep postgres-data      # 예: ieumgil_postgres-data
```

접두사가 `ieumgil` 이 아니면 `docker-compose.prod.yml` 의 `name:` 을 그 값으로 바꾼다.

---

## 1. 이미지 빌드 + 푸시 — 소스가 있는 곳

EC2 에는 저장소가 없다. 이미지는 로컬 PC 에서 굽는다.

**왜 여기서 굽나** — `VITE_*` 는 런타임 환경변수가 아니라 **이미지를 만들 때 번들에
구워지는 값**이다. 도메인·카카오 Redirect URI 를 EC2 의 환경변수로 넣어도 아무 일도
일어나지 않는다.

배포용 env 파일은 따로 두지 않는다. **개발용 `frontend/.env` 를 그대로 쓰고,
배포와 다른 한 줄만 셸에서 덮어쓴다** — 셸 환경변수가 `.env` 보다 우선한다.
실제로 다른 값은 `VITE_KAKAO_REDIRECT_URI` 하나뿐이다(`VITE_API_BASE_URL` 은 이미
상대경로 `/api` 이고, 카카오 키는 같은 앱이며, `VITE_API_PROXY_TARGET` 은 빌드 인자가
아니라 무시된다).

```bash
TAG=$(git rev-parse --short HEAD)
REPO=<계정>

cd frontend
VITE_KAKAO_REDIRECT_URI=https://i15a107.p.ssafy.io/oauth/kakao/callback \
  FRONTEND_IMAGE_REPO=$REPO/ieumgil-frontend IMAGE_TAG=$TAG \
  docker compose build
docker push $REPO/ieumgil-frontend:$TAG

cd ../backend
docker build -t $REPO/ieumgil-backend:$TAG .
docker push $REPO/ieumgil-backend:$TAG
```

덮어쓰기를 잊으면 **Dockerfile 이 "localhost 입니다" 로 빌드를 끊는다.** 조용히
잘못된 번들이 나가서 배포 후 카카오 로그인이 전부 실패하는 것보다 낫다.

프론트·백엔드를 **같은 태그**로 굽는다. 태그가 어긋나면 어떤 조합이 돌고 있는지
추적할 수 없고, 롤백할 때도 짝을 못 맞춘다.

---

## 2. EC2 스택 기동

처음 한 번만 폴더와 파일 두 개를 만든다.

```bash
mkdir -p ~/ieumgil && cd ~/ieumgil
# 저장소의 docker-compose.prod.yml → docker-compose.yml 로 저장
# 저장소의 .env.prod.example      → .env             로 저장
vi .env
```

기존 백엔드 `.env` 가 EC2 에 있다면 값을 그대로 옮겨 오고, HTTPS 로 바뀌면서
달라지는 다섯 줄만 확인한다.

```diff
+ BACKEND_IMAGE=<계정>/ieumgil-backend:<§1 의 태그>
+ FRONTEND_IMAGE=<계정>/ieumgil-frontend:<§1 의 태그>

- CORS_ALLOWED_ORIGINS=http://i15a107.p.ssafy.io:5173
+ CORS_ALLOWED_ORIGINS=https://i15a107.p.ssafy.io

- AUTH_REFRESH_COOKIE_SECURE=false
+ AUTH_REFRESH_COOKIE_SECURE=true

  AUTH_REFRESH_COOKIE_SAME_SITE=Lax        # ← 그대로 둔다 (아래 설명)

- KAKAO_REDIRECT_URI=http://localhost:5173/oauth/kakao/callback
+ KAKAO_REDIRECT_URI=https://i15a107.p.ssafy.io/oauth/kakao/callback
```

**`SameSite` 를 `None` 으로 바꾸지 않는다.** nginx 가 같은 도메인에서 `/api` 를
프록시하므로 브라우저 입장에서 완전한 same-origin 이고, `Lax` 가 CSRF 측면에서
더 안전하다. 개발 환경(vite 프록시)과도 같은 모양이 유지된다.

```bash
docker login                     # 비공개 레포지토리면 필요
docker compose pull
docker compose up -d
```

> `up -d` 는 **바뀐 서비스만 재생성한다.** 프론트 태그만 올렸다면 postgres·redis 는
> 건드리지 않는다. **`down` 은 쓰지 말 것** — 스택 전체가 내려간다.

동작 확인:

```bash
docker compose ps
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8081/api/projects  # 401 = 정상 기동
curl -I http://127.0.0.1:5173/          # 200
curl -I http://127.0.0.1:5173/groups/1  # 200 (SPA fallback — 404 면 이미지 안 nginx.conf 확인)
```

---

## 3. 호스트 nginx 설정

기존 443 server 블록에 아래 location 들을 넣는다. HTTP→HTTPS 리다이렉트 블록은
이미 있을 것이므로 건드리지 않는다.

```nginx
server {
    listen 443 ssl;
    http2 on;
    server_name i15a107.p.ssafy.io;

    ssl_certificate     /etc/letsencrypt/live/i15a107.p.ssafy.io/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/i15a107.p.ssafy.io/privkey.pem;

    # ── 프론트엔드 정적 ──────────────────────────────────
    # SPA fallback·캐시 헤더는 컨테이너 안 nginx 가 이미 처리한다. 여기선 넘기기만.
    location / {
        proxy_pass http://127.0.0.1:5173;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # ── REST ────────────────────────────────────────────
    # proxy_pass 에 경로를 붙이지 않는다 — /api 접두사를 그대로 백엔드에 넘겨야 한다
    # (vite dev 프록시도 rewrite 없이 넘기므로 개발/배포가 같은 모양이 된다).
    location /api/ {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        # ⚠️ 이 줄이 없으면 백엔드가 요청을 HTTP 로 보고 Secure 쿠키 판정이 틀어진다
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # ── STOMP WebSocket (실시간 협업·커서·보이스 시그널) ──
    location /ws {
        proxy_pass http://127.0.0.1:8081;

        # ⚠️ 이 세 줄이 핵심이다. 없으면 Upgrade 요청이 일반 HTTP 로 중계되다
        #    실패하고, 실시간 기능 전체(동시 편집·커서·음성)가 조용히 죽는다.
        proxy_http_version 1.1;
        proxy_set_header Upgrade    $http_upgrade;
        proxy_set_header Connection "upgrade";

        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # STOMP 하트비트는 10초라 기본 60초로도 끊기지 않지만, 유휴 탭에서
        # 하트비트가 밀릴 때를 대비해 넉넉히 둔다
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }

    # ── API 문서 차단 ────────────────────────────────────
    # 배포 서버의 Swagger 가 인증 없이 공개돼 있었다. 엔드포인트·DTO·에러코드가
    # 그대로 노출되므로 외부에서는 닫는다.
    location ~ ^/(swagger-ui|v3/api-docs|swagger-resources) {
        return 404;
    }
}
```

적용:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

### nginx 가 컨테이너인 경우

호스트 설치본이 아니라 컨테이너로 돌고 있다면 `127.0.0.1` 로는 프론트 컨테이너에
닿지 않는다. 두 가지 중 하나로 바꾼다.

- **(간단)** `proxy_pass http://host.docker.internal:5173;` + compose 에
  `extra_hosts: ["host.docker.internal:host-gateway"]`
- **(정석)** 외부 네트워크를 만들어 두 스택이 공유하고 서비스명으로 붙는다.
  이때는 `ports` 매핑을 지우고 `proxy_pass http://frontend:80;` 을 쓴다.

  ```bash
  docker network create ieumgil-web
  ```

  ```yaml
  # frontend/docker-compose.yml
  services:
    frontend:
      networks: [ieumgil-web]
  networks:
    ieumgil-web:
      external: true
  ```

---

## 4. 검증

```bash
# 8081 이 외부에서 열려 있지 않은지 (보안그룹에서 닫는 것이 정석)
curl -m 5 -I http://i15a107.p.ssafy.io:8081/api/health || echo "차단됨 ✓"
```

브라우저에서:

- [ ] `https://i15a107.p.ssafy.io` 접속 — 자물쇠 표시
- [ ] **딥링크 새로고침** — `/groups/1/projects/1` 을 주소창에 직접 입력해 200
- [ ] 카카오 로그인 왕복
- [ ] **로그인 후 새로고침해도 유지** — refresh 쿠키가 Secure 로 저장됐다는 뜻
- [ ] DevTools > Network > WS 탭에서 `/ws` 가 **101 Switching Protocols**
- [ ] 두 브라우저(다른 계정)에서 블록 이동이 실시간 반영
- [ ] 라이브 커서가 상대 화면에 보임
- [ ] **보이스 연결** — HTTPS 가 되면서 `getUserMedia` 가 처음으로 풀린다
- [ ] 지도 렌더 + 장소 검색
- [ ] 초대 링크 복사 (`navigator.clipboard` 도 secure context 필요)

`/ws` 가 101 이 아니라 200/400 이면 §3 의 Upgrade 헤더를 확인한다.

---

## 5. 롤백

이미지 태그를 커밋 SHA 로 찍어 뒀다면 EC2 에서 태그만 바꾸면 된다. 재빌드도,
소스도 필요 없다.

```bash
cd ~/ieumgil
vi .env                          # BACKEND_IMAGE·FRONTEND_IMAGE 태그를 이전 SHA 로
docker compose pull && docker compose up -d
```

nginx 만 되돌리려면 server 블록의 location 들을 주석 처리하고 `nginx -t && systemctl reload nginx`.

**DB 마이그레이션은 되돌리지 않는다.** 006 의 새 컬럼이 있어도 구버전 코드는 무시한다.
단 **007 을 실행한 뒤에는 구버전으로 돌아갈 수 없다**(옛 컬럼이 사라진다) — 그래서
007 은 발표가 끝난 뒤에 한다.

---

## 6. 재배포 (코드만 바뀐 경우)

§1 → §2 만 반복한다. `.env.prod` 의 태그 두 줄이 바뀌는 전부다.

```bash
# 소스가 있는 곳
git pull
TAG=$(git rev-parse --short HEAD)

REPO=<계정>

cd frontend
VITE_KAKAO_REDIRECT_URI=https://i15a107.p.ssafy.io/oauth/kakao/callback \
  FRONTEND_IMAGE_REPO=$REPO/ieumgil-frontend IMAGE_TAG=$TAG docker compose build
docker push $REPO/ieumgil-frontend:$TAG

cd ../backend && docker build -t $REPO/ieumgil-backend:$TAG .
docker push $REPO/ieumgil-backend:$TAG
```

```bash
# EC2
cd ~/ieumgil
vi .env                          # BACKEND_IMAGE·FRONTEND_IMAGE 태그를 새 SHA 로
docker compose pull && docker compose up -d
```

`VITE_*` 값만 바꿨을 때도 **이미지를 다시 구워야 한다.** 번들에 구워지는 값이라
EC2 의 `.env` 수정으로는 반영되지 않는다.

---

## 남은 보안 항목

배포와 별개로 정리하면 좋은 것들. 급하지 않은 순서.

| 항목 | 현재 | 해야 할 것 |
|---|---|---|
| 8081 외부 노출 | 열림 | 보안그룹에서 차단 (nginx 만 경유) |
| Swagger | 공개 | §3 에서 nginx 차단. 프로파일로 아예 끄는 편이 낫다 |
| 카카오 REST 키 | 번들에 노출 | 지도/장소용 앱을 분리하고 JS 키로 교체 (OAuth 는 기존 앱 유지 — 옮기면 기존 회원이 전부 신규가 된다) |
| TURN 서버 | 없음 | 다른 네트워크 간 음성이 안 된다. 데모 범위 밖 |
