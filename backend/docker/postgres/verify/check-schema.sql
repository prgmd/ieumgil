-- 스키마 제약·인덱스 점검 (읽기 전용 — 아무것도 변경하지 않는다)
--
-- 왜 필요한가:
--   이 프로젝트는 마이그레이션 도구 없이 Hibernate ddl-auto에 스키마를 맡긴다.
--   @OnDelete(CASCADE)는 테이블을 "새로 만들 때만" DDL에 반영되고, ddl-auto=update는
--   기존 제약을 고치지 않는다. 볼륨을 언제 만들었느냐에 따라 같은 코드가 다른 스키마
--   위에서 돌 수 있다는 뜻이다.
--
--   CASCADE가 빠져 있으면 GroupCleanupScheduler(매일 04:00 하드 삭제)가 FK 위반으로
--   실패한다. 배포 전·볼륨 재생성 후·팀원 환경 이슈 재현 시 이 스크립트로 확인한다.
--
-- 실행:
--   docker exec -i ieumgil-postgres sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB' \
--     < backend/docker/postgres/verify/check-schema.sql

\pset border 2

\echo ''
\echo '========== 1) 외래키 ON DELETE 동작 =========='
\echo '기대: group_member.group_id / group_member.member_id / project.group_id'
\echo '      block.project_id / activity_log.project_id  → 전부 ON DELETE CASCADE'
\echo '예외: block.author_id, activity_log.member_id     → CASCADE 없음(탈퇴는 소프트 삭제)'
\echo ''

SELECT src.relname                 AS "테이블",
       pg_get_constraintdef(c.oid) AS "FK 정의"
FROM pg_constraint c
JOIN pg_class src ON src.oid = c.conrelid
JOIN pg_namespace n ON n.oid = src.relnamespace
WHERE c.contype = 'f' AND n.nspname = 'public'
ORDER BY src.relname, c.conname;

\echo ''
\echo '========== 2) 인덱스 현황 =========='
\echo '기대 (PK·UNIQUE 외 수동 선언분):'
\echo '  ix_group_member_member       group_member(member_id)'
\echo '  ix_project_group             project(group_id, deleted_at)'
\echo '  ix_block_chain               block(project_id, start_offset_minutes, order_key, id) WHERE deleted_at IS NULL'
\echo '  ux_activity_log_project_seq  activity_log(project_id, seq) UNIQUE'
\echo ''

SELECT tablename AS "테이블", indexname AS "인덱스", indexdef AS "정의"
FROM pg_indexes
WHERE schemaname = 'public'
ORDER BY tablename, indexname;

\echo ''
\echo '========== 3) 테이블 목록 =========='

SELECT tablename AS "테이블"
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY tablename;
