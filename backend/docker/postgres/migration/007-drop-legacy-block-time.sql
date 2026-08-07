-- 006 의 이관 결과를 확인한 뒤에만 실행한다. 비가역이다.
--
--   SELECT count(*) FILTER (WHERE day_no IS NOT NULL)                AS on_day,
--          count(*) FILTER (WHERE start_offset_minutes IS NOT NULL)  AS migrated
--     FROM block;
--
-- 두 수가 다르면 실행하지 말 것.
--
-- end_time 은 이관 대상이 아니었다 — start + duration 파생을 굳혀둔 값이고
-- 서버는 한 번도 읽지 않았다.

ALTER TABLE block DROP COLUMN IF EXISTS day_no;
ALTER TABLE block DROP COLUMN IF EXISTS start_time;
ALTER TABLE block DROP COLUMN IF EXISTS end_time;
