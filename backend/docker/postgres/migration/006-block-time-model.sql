-- 블록 시각 모델: day_no + start_time + end_time → start_offset_minutes 단일 컬럼
--
-- 가역 단계다. 옛 컬럼은 그대로 살아 있고, 이관이 잘못돼도 다시 계산할 수 있다.
-- 컬럼 제거는 007 에서 따로 한다.
--
-- end_time 은 이관하지 않는다 — start + duration 파생을 굳혀둔 값이라 옮길 정보가 없다.

ALTER TABLE block ADD COLUMN IF NOT EXISTS start_offset_minutes integer;

UPDATE block
   SET start_offset_minutes = (day_no - 1) * 1440
       + COALESCE(EXTRACT(HOUR FROM start_time) * 60
                + EXTRACT(MINUTE FROM start_time), 0)
 WHERE day_no IS NOT NULL
   AND start_offset_minutes IS NULL;

-- 옛 인덱스는 day_no 가 가운데라 ORDER BY order_key, id 를 태우지 못했다.
-- 새 인덱스는 findChain 의 ORDER BY 전체와 범위 스캔을 동시에 태운다.
DROP INDEX IF EXISTS ix_block_chain;
CREATE INDEX IF NOT EXISTS ix_block_chain
    ON block (project_id, start_offset_minutes, order_key, id)
    WHERE deleted_at IS NULL;

-- 007(DROP) 실행 전 확인용. 두 수가 같아야 한다.
--   SELECT count(*) FILTER (WHERE day_no IS NOT NULL)      AS on_day,
--          count(*) FILTER (WHERE start_offset_minutes IS NOT NULL) AS migrated
--     FROM block;
-- 표본 대조:
--   SELECT id, day_no, start_time, start_offset_minutes FROM block
--    WHERE day_no IS NOT NULL ORDER BY id LIMIT 10;
