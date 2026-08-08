# 이음길 — 포팅 매뉴얼

여행 일정을 여러 명이 **동시에** 만드는 실시간 협업 서비스.

| 문서 | 내용 |
|---|---|
| [01-빌드-배포-매뉴얼.md](./01-빌드-배포-매뉴얼.md) | 클론 → 로컬 Docker Compose 실행. 제품·버전, 환경 변수, 특이사항, 계정·프로퍼티 파일 목록 |
| [02-외부-서비스.md](./02-외부-서비스.md) | 카카오·ODsay·TourAPI·GMS 등 가입과 키 발급 |
| [03-DB-덤프.md](./03-DB-덤프.md) | 덤프 생성·복원, 스키마 마이그레이션 순서 |
| [04-시연-시나리오.md](./04-시연-시나리오.md) | 화면별·클릭별 시연 순서 |

> EC2 + nginx(HTTPS) 운영 배포 절차는 [`docs/deploy.md`](../docs/deploy.md) 와
> [`docs/deploy-checklist.md`](../docs/deploy-checklist.md) 에 따로 있다.

---

## 세 줄 요약

```bash
git clone <저장소 URL> ieumgil && cd ieumgil
cd backend  && cp .env.example .env && vi .env && docker compose up -d --build
cd ../frontend && cp .env.example .env && vi .env && npm install && npm run dev
```

→ `http://localhost:5173`

---

## 구성

```
브라우저 ──> Vite dev server (5173)
               ├─ 정적 자원
               ├─ /api  ─┐
               └─ /ws   ─┤ 프록시
                         ↓
               ┌─ Docker Compose (backend/docker-compose.yml) ─┐
               │  backend (8080) ── postgres (5432)            │
               │                 └─ redis (6379)               │
               └───────────────────────────────────────────────┘
```

프론트엔드는 컨테이너에 올리지 않는다. Vite dev server 가 `/api`·`/ws` 를 백엔드로
프록시하므로 브라우저 입장에서 **단일 오리진**이 되고, 이 구조가 배포(nginx
리버스 프록시)와 같은 모양이다.

## 제품·버전 요약

| 구분 | 제품 | 버전 |
|---|---|---|
| JVM | Eclipse Temurin JDK / JRE | **21** |
| 프레임워크 | Spring Boot | **3.4.13** |
| WAS | 내장 Tomcat (starter-web) | Boot 3.4.13 동봉 |
| 빌드 도구 | Gradle Wrapper | **8.12** |
| DB | PostgreSQL | **16-alpine** |
| 캐시·세션 | Redis | **7-alpine** |
| 프론트 런타임 | Node.js | **20 이상** |
| 프론트 빌드 | Vite | **8.x** |
| 프론트 | React | **19.x** |
| 컨테이너 | Docker Compose | v2 |
