-- 교통 후보 API(2026-08-03-transit-candidates-v2) 실측 검증용 시나리오 시드
--
-- Task 14: 3박4일 서울->부산->제주 여행으로 시내·시외·도서·복합 구간을 한 번에 확인한다.
-- PUBLIC 프로젝트 하나, CAR 프로젝트 하나를 같은 블록 구성으로 둬서 같은 구간에서
-- 후보 집합이 실제로 갈리는지 본다.
--
-- 실행:
--   docker exec -i ieumgil-postgres sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB' \
--     < docker/postgres/seed/transit-candidate-scenario-seed.sql
--
-- 전제: users.id = 1 이 있어야 한다(카카오 로그인 1회로 생성됨). dev-seed.sql과 달리
-- 기존 그룹/프로젝트를 지우지 않는다 — 전용 그룹(9001)과 프로젝트(9001/9002)만 추가한다.
--
-- Day1  09:00 집결(서울시청) -> 서울역 -> [시외] -> 부산역 -> 해운대
-- Day2  부산 시내 이동만 (해운대 -> 광안리 -> 감천문화마을)
-- Day3  부산역 -> [도서·항공] -> 제주공항 -> 성산일출봉
-- Day4  제주 시내 (성산일출봉 -> 제주시청)
--
-- 호출은 Day 하나의 블록 체인만 담아 4번(Day1~4) 나눠 보낸다 — 이 서비스의 시외 확정
-- 플래그(intercityUsed)와 기준 시각 누적기(SegmentClock)는 한 calculate() 호출 범위에서만
-- 유지되므로, 여러 Day를 한 요청에 몰아 보내면 두 번째 시외 구간이 "앞선 시외 구간의 편이
-- 확정되지 않았습니다"로 잘못 건너뛰어진다(Task 14 실측에서 재현 확인, task-14-report.md 참고).
--
-- 성산일출봉 좌표는 실제 정상부(33.4587,126.9425)가 아니라 주차장·매표소 인근
-- (33.4593,126.9401)을 쓴다 — 정상부 좌표는 카카오 길찾기가 "시작 지점 주변의 도로를
-- 탐색할 수 없음"(result_code 102)을 반환해 자차·택시 후보가 통째로 빠진다.

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM users WHERE id = 1) THEN
        RAISE EXCEPTION
            'users.id = 1 이 없습니다. 카카오 로그인을 한 번 해서 계정을 만든 뒤 다시 실행하세요.';
    END IF;
END $$;

INSERT INTO travel_group (id, name, invite_code, invite_expires_at, created_at, updated_at)
VALUES (9001, '교통후보 실측검증', 'TCAND999', now() + interval '30 days', now(), now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO group_member (group_id, member_id, joined_at)
VALUES (9001, 1, now())
ON CONFLICT (group_id, member_id) DO NOTHING;

INSERT INTO project (id, name, group_id, status, start_date, end_date, transport_pref, destination, created_at, updated_at)
VALUES
  (9001, '실측검증-PUBLIC', 9001, 'PLANNING', '2026-08-10', '2026-08-13', 'PUBLIC', '부산·제주', now(), now()),
  (9002, '실측검증-CAR',    9001, 'PLANNING', '2026-08-10', '2026-08-13', 'CAR',    '부산·제주', now(), now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO block (project_id, author_id, day_no, order_key, category, name, lat, lng,
                    duration_min, budget, is_time_fixed, source, field_updated_at, created_at, updated_at)
SELECT p.id, 1, d.day_no, d.order_key, 'SPOT', d.name, d.lat, d.lng, d.duration_min, 0, false, 'MANUAL', '{}'::jsonb, now(), now()
FROM (VALUES (9001), (9002)) AS p(id)
CROSS JOIN (VALUES
  (1, 'a0', '서울시청',     37.5665, 126.9780, 0),
  (1, 'a1', '서울역',       37.5547, 126.9707, 10),
  (1, 'a2', '부산역',       35.1152, 129.0414, 10),
  (1, 'a3', '해운대',       35.1587, 129.1604, 60),
  (2, 'b0', '해운대',       35.1587, 129.1604, 0),
  (2, 'b1', '광안리',       35.1532, 129.1187, 60),
  (2, 'b2', '감천문화마을', 35.0980, 129.0106, 90),
  (3, 'c0', '부산역',       35.1152, 129.0414, 0),
  (3, 'c1', '제주공항',     33.5113, 126.4930, 10),
  (3, 'c2', '성산일출봉',   33.4593, 126.9401, 90),
  (4, 'd0', '성산일출봉',   33.4593, 126.9401, 0),
  (4, 'd1', '제주시청',     33.4996, 126.5312, 60)
) AS d(day_no, order_key, name, lat, lng, duration_min)
ORDER BY p.id, d.day_no, d.order_key;

COMMIT;
