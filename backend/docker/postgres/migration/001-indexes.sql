-- 001: 그룹·프로젝트 조회 인덱스
--
-- 왜 수동 스크립트인가:
--   엔티티에 @Table(indexes=...)로 같은 인덱스가 선언돼 있어 로컬(ddl-auto=update)은
--   재기동만으로 생성된다. 하지만 배포는 DDL_AUTO=none이라 스크립트 반영이 유일한 경로다.
--   전부 IF NOT EXISTS라 몇 번을 다시 돌려도 안전하다(멱등).
--
-- 왜 필요한가:
--   PostgreSQL은 FK 컬럼에 인덱스를 자동 생성하지 않는다.
--   - group_member PK는 (group_id, member_id) 순서라 member_id 단독 조회
--     (내 그룹 목록, 회원 탈퇴 시 소속 정리)가 PK 인덱스를 못 탄다.
--   - project 카드 목록·그룹별 개수 집계는 전부 (group_id, deleted_at) 조건이다.
--
-- 실행:
--   docker exec -i ieumgil-postgres sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB' \
--     < backend/docker/postgres/migration/001-indexes.sql

CREATE INDEX IF NOT EXISTS ix_group_member_member
    ON group_member (member_id);

CREATE INDEX IF NOT EXISTS ix_project_group
    ON project (group_id, deleted_at);
