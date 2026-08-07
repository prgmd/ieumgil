# 배포 실행 체크리스트

**위에서 아래로 따라가는 문서.** 각 단계의 "확인"을 통과한 뒤 다음으로 넘어간다 —
깨진 단계를 안고 진행하면 마지막에 원인을 못 찾는다.

배경 설명·nginx 전문은 [deploy.md](./deploy.md) 에 있다. 이 문서는 순서만 다룬다.

| | |
|---|---|
| 대상 | `https://i15a107.p.ssafy.io` (실제 도메인으로 바꿔 읽을 것) |
| 예상 소요 | 30~45분 (①의 카카오 반영 대기 제외) |

```
① 카카오 콘솔 등록 2곳          반영 지연이 있어 가장 먼저
② DB 마이그레이션 006           ★ 안 하면 기존 일정이 전부 후보로 내려간다
③ 이미지 빌드 + 푸시            소스 있는 곳(로컬 PC)
④ EC2 에 compose·.env 배치      처음 한 번만
⑤ pull → up -d                  스택 4개(front·back·db·redis)가 한 번에
⑥ nginx 서버블록                nginx -t && systemctl reload
⑦ 검증
```

**EC2 에는 저장소를 클론하지 않는다.** 이미지를 받아 켜기만 하므로, EC2 에 필요한
파일은 `docker-compose.yml` 과 `.env` 두 개뿐이다.

---

## 사전 준비

```bash
ssh <user>@i15a107.p.ssafy.io
docker ps                        # 지금 떠 있는 컨테이너 확인
sudo nginx -t                    # 호스트 nginx 와 인증서
```

- [ ] postgres·redis·backend 세 컨테이너가 `Up`
- [ ] 현재 EC2 의 compose 파일 위치를 안다 (`docker inspect ieumgil-backend | grep -i compose`)

> **현재 스택의 프로젝트 이름을 반드시 확인한다.** 볼륨 이름이
> `<프로젝트명>_postgres-data` 로 결정되므로, 새 compose 의 `name:` 이 다르면
> **빈 DB 가 새로 생긴다**(기존 데이터는 옛 볼륨에 그대로 남지만 안 보인다).
>
> ```bash
> docker volume ls | grep postgres-data      # 예: ieumgil_postgres-data
> ```
>
> 접두사가 `ieumgil` 이 아니면 `docker-compose.prod.yml` 의 `name:` 을 그 값으로 바꾼다.

---

## ① 카카오 콘솔 등록 2곳

[카카오 개발자센터](https://developers.kakao.com) > 내 애플리케이션.
**서버 작업보다 먼저** — 반영에 시간이 걸릴 수 있다.

| 메뉴 | 넣을 값 |
|---|---|
| 카카오 로그인 > Redirect URI | `https://i15a107.p.ssafy.io/oauth/kakao/callback` |
| 앱 설정 > 플랫폼 > Web > 사이트 도메인 | `https://i15a107.p.ssafy.io` |

- [ ] Redirect URI 등록 — 기존 localhost 항목은 **지우지 말 것**(로컬 개발이 막힌다)
- [ ] 사이트 도메인 등록

> **두 번째를 빠뜨리기 쉽다.** 없어도 로그인은 되는데 **지도만 회색으로** 나온다.
>
> Redirect URI 는 **끝 슬래시까지 정확히** 같아야 한다. 하나라도 다르면 KOE006.

---

## ② DB 마이그레이션 ★

`DDL_AUTO=update` 라면 **컬럼은 자동으로 생기지만 데이터 이관은 되지 않는다.**
Hibernate 는 `ALTER TABLE ADD COLUMN` 만 할 뿐 값을 채우지 않는다. 그래서
데이터를 옮기는 마이그레이션은 반드시 손으로 돌려야 한다.

### 006 — 블록 시간 모델 (필수)

시간 모델이 `day_no + start_time` → `start_offset_minutes` 로 바뀌었다.
**이걸 안 돌리면 기존 블록이 전부 `NULL` 이 되고, 프론트는 그걸 후보(POOL)로
해석한다 — 모든 일정이 타임라인에서 사라져 후보 목록으로 내려간다.**

```bash
docker exec -i ieumgil-postgres sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB' \
  < backend/docker/postgres/migration/006-block-time-model.sql
```

**확인** — 두 수가 같아야 한다.

```bash
docker exec -i ieumgil-postgres sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB -c \
  "SELECT count(*) FILTER (WHERE day_no IS NOT NULL) AS on_day, \
          count(*) FILTER (WHERE start_offset_minutes IS NOT NULL) AS migrated FROM block"'
```

- [ ] `on_day` 와 `migrated` 가 같다
- [ ] 표본 대조 — `SELECT id, day_no, start_time, start_offset_minutes FROM block WHERE day_no IS NOT NULL LIMIT 10;`
      에서 `(day_no-1)*1440 + 시각` 이 맞는다

### 005 — 마지막 편집자 (확인만)

`DDL_AUTO=update` 면 컬럼은 이미 생겨 있다. 값(백필)만 없는데, 프론트가
작성자로 폴백하므로 화면은 정상이다. 맞추고 싶으면 한 줄이면 된다.

```bash
docker exec -i ieumgil-postgres sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB -c \
  "UPDATE block SET last_edited_by = author_id WHERE last_edited_by IS NULL"'
```

- [ ] `\d block` 에 `last_edited_by` 가 있다

### 007 — 옛 컬럼 삭제 (하지 않는다)

**비가역이다.** 006 이 잘 돌았는지 며칠 지켜본 뒤, 발표가 끝나고 하는 게 맞다.

---

## ③ 이미지 빌드 + 푸시 — 소스가 있는 곳 (로컬 PC)

배포용 env 파일은 따로 만들지 않는다. **개발용 `frontend/.env` 를 그대로 쓰고,
배포와 값이 다른 한 줄만 셸에서 덮어쓴다** — 셸 환경변수가 `.env` 보다 우선한다.

| 변수 | 개발 | 배포 |
|---|---|---|
| `VITE_API_BASE_URL` | `/api` | 같음 |
| `VITE_KAKAO_REST_API_KEY` | 같은 카카오 앱 | 같음 |
| `VITE_API_PROXY_TARGET` | dev server 전용 | 빌드 인자가 아니라 무시됨 |
| **`VITE_KAKAO_REDIRECT_URI`** | `localhost:5173` | **https 도메인** ← 이것만 |

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

> 덮어쓰기를 잊으면 **Dockerfile 이 "localhost 입니다" 로 빌드를 끊는다.**
> 조용히 잘못된 번들이 나가서 배포 후 로그인이 전부 실패하는 것보다 낫다.
>
> `VITE_*` 는 런타임 값이 아니라 **이미지에 구워지는 값**이다. 도메인이나 카카오
> Redirect URI 를 바꾸려면 EC2 가 아니라 여기서 바꾸고 다시 구워야 한다.

- [ ] 프론트·백엔드를 **같은 태그**로 구웠다 (짝이 어긋나면 롤백할 때 맞출 수 없다)
- [ ] 두 이미지 push 완료 — 레지스트리에서 태그가 보인다

---

## ④ EC2 에 파일 배치 — 처음 한 번만

```bash
mkdir -p ~/ieumgil && cd ~/ieumgil
# 저장소의 docker-compose.prod.yml 내용을 docker-compose.yml 로 저장
# 저장소의 .env.prod.example  내용을 .env             로 저장
vi .env
```

기존 백엔드 `.env` 가 EC2 에 이미 있다면 값을 그대로 옮겨 오고, 아래 다섯 줄만
새로 확인한다.

```bash
BACKEND_IMAGE=<계정>/ieumgil-backend:<③의 태그>
FRONTEND_IMAGE=<계정>/ieumgil-frontend:<③의 태그>
CORS_ALLOWED_ORIGINS=https://i15a107.p.ssafy.io
AUTH_REFRESH_COOKIE_SECURE=true
KAKAO_REDIRECT_URI=https://i15a107.p.ssafy.io/oauth/kakao/callback
```

- [ ] `docker-compose.yml` 의 `name:` 이 기존 볼륨 접두사와 같다 (사전 준비 참고)
- [ ] `.env` 에 DB 비밀번호·JWT_SECRET·외부 API 키가 **기존 값 그대로** 들어갔다
- [ ] 옛 compose 파일이 있던 폴더는 지우지 말고 남겨 둔다 (되돌릴 때 필요)

---

## ⑤ pull → up

```bash
cd ~/ieumgil
docker login                     # 비공개 레포지토리면 필요
docker compose pull
docker compose up -d
```

> `up -d` 는 **바뀐 서비스만 재생성한다.** 프론트 태그만 올렸다면 postgres·redis 는
> 건드리지 않는다.
>
> **`down` 을 쓰지 말 것** — 스택 전체가 내려간다(볼륨은 살지만 DB 다운타임).

**확인**

```bash
docker compose ps
docker compose logs --tail=50 backend
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8081/api/projects  # 401 = 정상 기동
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:5173/              # 200
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:5173/groups/1      # 200 (SPA fallback)
```

- [ ] 네 컨테이너 모두 `Up` / `Up (healthy)`
- [ ] 백엔드 curl 이 **401** — 500 이면 ② 를 다시 확인
- [ ] 프론트 curl 두 개가 **200** — 두 번째가 404 면 이미지 안 `nginx.conf` 의 `try_files`

---

## ⑥ nginx 서버블록

기존 443 server 블록 안에 location 4개를 추가한다. **전문은
[deploy.md §3](./deploy.md#3-호스트-nginx-설정) 에서 복사할 것.**

| location | 대상 | 빠뜨리면 |
|---|---|---|
| `/` | `127.0.0.1:5173` | 화면이 안 뜬다 |
| `/api/` | `127.0.0.1:8081` + `X-Forwarded-Proto` | 로그인 유지가 안 된다 |
| `/ws` | `127.0.0.1:8081` + **Upgrade 헤더 3줄** | 실시간 기능 전체가 조용히 죽는다 |
| `~ ^/(swagger-ui\|v3/api-docs)` | `return 404` | API 문서가 공개된 채로 남는다 |

```bash
sudo nginx -t && sudo systemctl reload nginx
```

- [ ] `nginx -t` 가 `test is successful`
- [ ] `curl -I https://i15a107.p.ssafy.io` 가 200

---

## ⑦ 검증

```bash
curl -m 5 -I http://i15a107.p.ssafy.io:8081/api/projects || echo "8081 차단됨 ✓"
curl -s -o /dev/null -w '%{http_code}\n' https://i15a107.p.ssafy.io/swagger-ui/index.html  # 404
```

브라우저에서 (**두 계정 / 두 브라우저**로):

- [ ] `https://...` 접속 — 자물쇠 표시
- [ ] **기존 프로젝트의 일정이 타임라인에 그대로 있다** ← ② 가 잘 됐다는 뜻
- [ ] **딥링크 새로고침** — `/groups/1/projects/1` 을 주소창에 직접 입력해 정상 렌더
- [ ] 카카오 로그인 왕복
- [ ] **로그인 후 새로고침해도 유지** ← refresh 쿠키가 Secure 로 저장됐다는 뜻
- [ ] DevTools > Network > WS 탭에서 `/ws` 가 **101 Switching Protocols**
- [ ] 두 브라우저에서 블록 이동이 실시간 반영
- [ ] 라이브 커서가 상대 화면에 보임
- [ ] **보이스 연결** ← HTTPS 가 되면서 `getUserMedia` 가 처음으로 풀린다
- [ ] 지도 렌더 + 장소 검색
- [ ] 초대 링크 복사 (`navigator.clipboard` 도 secure context 필요)

---

## 증상별 되짚기

| 증상 | 먼저 볼 곳 |
|---|---|
| **일정이 전부 후보 목록으로 내려감** | ② 마이그레이션 006 |
| DB 가 비어 보임 | compose `name:` 이 기존 볼륨 접두사와 다르다 (사전 준비) |
| 지도만 회색 | ① 사이트 도메인 등록 |
| 로그인 실패 (KOE006) | ① Redirect URI 와 ③ `VITE_KAKAO_REDIRECT_URI` 가 **글자까지** 같은지 |
| 로그인은 되는데 새로고침하면 풀림 | ④ `AUTH_REFRESH_COOKIE_SECURE=true`, ⑥ `/api/` 의 `X-Forwarded-Proto` |
| 실시간 반영이 안 됨 (에러도 없음) | ⑥ `/ws` 의 Upgrade 헤더 3줄 |
| 블록 조회 500 | ② 마이그레이션 |
| 딥링크만 404 | 이미지 안 `nginx.conf` 의 `try_files` |
| 배포했는데 옛 화면 | 강력 새로고침. 그래도면 `index.html` 의 `no-store` |
| 화면은 뜨는데 API 가 전부 실패 | ③ 에서 `--env-file` 을 빠뜨렸을 가능성. 다시 굽는다 |

---

## 재배포 (코드만 바뀐 경우)

①·②·④·⑥ 은 다시 하지 않는다. ③ → ⑤ 만 반복한다.

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

**`VITE_*` 값만 바꿨을 때도 이미지를 다시 구워야 한다.** 번들에 구워지는 값이라
EC2 의 `.env` 수정으로는 반영되지 않는다.

### 롤백

EC2 `.env` 의 태그를 이전 SHA 로 되돌리고 `pull && up -d`. 그래서 태그를
`latest` 가 아니라 커밋 SHA 로 찍어 두는 것이다.

DB 마이그레이션은 되돌리지 않는다 — 006 의 새 컬럼이 있어도 구버전 코드는 무시한다.
단 **007 을 실행한 뒤에는 구버전으로 못 돌아간다**(옛 컬럼이 사라진다).
