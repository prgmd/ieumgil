-- 002: 대시보드(block · activity_log) 인덱스
--
-- 전제: block · activity_log 테이블이 이미 존재해야 한다 (Step 1 엔티티 반영 후 실행).
-- 전부 IF NOT EXISTS라 몇 번을 다시 돌려도 안전하다(멱등).
--
-- ix_block_chain은 partial index다 — JPA @Index로는 WHERE 절을 표현할 수 없어
-- ddl-auto가 어떤 모드여도 이 스크립트로만 생성된다. 체인 조회(스냅샷·이동)는 항상
-- deleted_at IS NULL 필터가 붙으므로, tombstone을 인덱스에서 아예 제외해 크기와 갱신 비용을 줄인다.
--
-- ux_activity_log_project_seq는 엔티티 @UniqueConstraint로도 선언돼 있다(신규 볼륨은 자동 생성).
-- 구볼륨 대비로 여기 한 번 더 둔다. 재전송 조회(WHERE project_id=? AND seq>? ORDER BY seq)도
-- 이 인덱스를 그대로 탄다 — 별도 조회용 인덱스가 필요 없다.
--
-- 실행:
--   docker exec -i ieumgil-postgres sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB' \
--     < backend/docker/postgres/migration/002-dashboard-indexes.sql

CREATE INDEX IF NOT EXISTS ix_block_chain
    ON block (project_id, day_no, order_key)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_activity_log_project_seq
    ON activity_log (project_id, seq);
