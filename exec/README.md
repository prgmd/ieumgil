# 이음길 — 포팅 매뉴얼

여행 일정을 여러 명이 **동시에** 만드는 실시간 협업 서비스.

| 문서 | 내용 |
|---|---|
| [01-빌드-배포-매뉴얼.md](./01-빌드-배포-매뉴얼.md) | 클론 → 빌드 → 실행. 제품·버전, 환경 변수, 특이사항, 계정·프로퍼티 파일 목록, EC2 배포 요약 |
| [02-외부-서비스.md](./02-외부-서비스.md) | 카카오·ODsay·TourAPI·GMS 등 가입과 키 발급 |
| [03-DB-덤프.md](./03-DB-덤프.md) | 덤프 생성·복원, 스키마 마이그레이션 순서 |
| [04-시연-시나리오.md](./04-시연-시나리오.md) | 화면별·클릭별 시연 순서 |

---

## 실행 요약

```bash
git clone <저장소 URL> ieumgil && cd ieumgil

cd backend
cp .env.example .env && vi .env          # DB·JWT·카카오 값 채우기
docker compose up -d --build backend     # backend + postgres + redis

cd ../frontend
cp .env.example .env && vi .env          # 카카오 키 + 프록시 대상
npm install
npm run dev
```

→ **`http://localhost:5173`**

## 구성

```
브라우저 ──> Vite dev server (5173)
               ├─ 정적 자원
               ├─ /api  ─┐
               └─ /ws   ─┤ 프록시 (ws: true)
                         ↓
               ┌─ Docker Compose ─────────────────────┐
               │  backend (8080) ── postgres (5432)   │
               │                 └─ redis (6379)      │
               └──────────────────────────────────────┘
```

프론트엔드는 컨테이너에 올리지 않는다. Vite dev server 가 `/api`·`/ws` 를 백엔드로
프록시하므로 브라우저 입장에서 **단일 오리진**이 되고, 이 구조가 배포(nginx 리버스
프록시)와 같은 모양이다.

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
| 프론트 번들러 | Vite | **8.x** |
| 프론트 | React | **19.x** |
| 컨테이너 | Docker Compose | v2 |

호스트에 JDK·Gradle 을 설치할 필요는 없다. 백엔드 빌드는 컨테이너 안에서 이뤄진다.
