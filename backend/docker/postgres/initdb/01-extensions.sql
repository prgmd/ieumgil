-- postgres 컨테이너 최초 기동 시 1회만 실행된다.
-- (postgres-data 볼륨이 이미 있으면 실행되지 않음 → 다시 돌리려면 volume 삭제 필요)

-- UUID 생성 함수 (gen_random_uuid)
CREATE EXTENSION IF NOT EXISTS pgcrypto;
