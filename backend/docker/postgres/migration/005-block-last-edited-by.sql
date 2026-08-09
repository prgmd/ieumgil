-- 005: 블록 마지막 편집자 영속 (PRS-04)
--
-- 카드의 "가장 최근 수정자" 아바타 배지가 op 기반 세션 메모리에만 있어
-- 새로고침하면 작성자로 되돌아가는 문제 — last_edited_by 컬럼으로 영속화한다.
-- author_id와 같은 이유로 ON DELETE 없음(탈퇴는 소프트 삭제라 FK가 깨지지 않는다).
-- 배포는 DDL_AUTO=none이라 이 스크립트가 유일한 반영 경로다(로컬 update는 자동).
--
-- 실행:
--   docker exec -i ieumgil-postgres sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB' \
--     < backend/docker/postgres/migration/005-block-last-edited-by.sql

ALTER TABLE block ADD COLUMN last_edited_by bigint REFERENCES users(id);

-- 기존 행 백필: 편집 이력이 없으므로 작성자로 시작한다 (클라이언트 폴백과 같은 의미)
UPDATE block SET last_edited_by = author_id;
