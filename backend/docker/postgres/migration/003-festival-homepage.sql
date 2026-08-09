-- 003: 축제 공식 홈페이지 컬럼
--
-- Festival.homepage 저장용. 배치가 detailCommon2로 채운다.
-- 배포는 DDL_AUTO=none이라 이 스크립트가 유일한 반영 경로다(로컬 update는 자동).
-- IF NOT EXISTS라 몇 번을 다시 돌려도 안전하다(멱등).
--
-- 실행:
--   docker exec -i ieumgil-postgres sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB' \
--     < backend/docker/postgres/migration/003-festival-homepage.sql

ALTER TABLE festival ADD COLUMN IF NOT EXISTS homepage VARCHAR(500);
