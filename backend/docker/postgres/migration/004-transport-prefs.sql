-- 004: 이동수단 선호 다중 선택 전환
--
-- 자차/대중교통 다중 선택: 스칼라 transport_pref → jsonb 배열 transport_prefs.
-- 배포는 DDL_AUTO=none이라 이 스크립트가 유일한 반영 경로다(로컬 update는 자동).
--
-- 실행:
--   docker exec -i ieumgil-postgres sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB' \
--     < backend/docker/postgres/migration/004-transport-prefs.sql

ALTER TABLE project ADD COLUMN transport_prefs jsonb;

UPDATE project SET transport_prefs =
  CASE transport_pref
    WHEN 'CAR'    THEN '["CAR"]'::jsonb
    WHEN 'PUBLIC' THEN '["PUBLIC"]'::jsonb
    ELSE '["PUBLIC"]'::jsonb          -- NULL 포함: 대중교통 기본
  END;

ALTER TABLE project DROP COLUMN transport_pref;
