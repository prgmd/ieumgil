/**
 * 백엔드가 응답 본문의 `code` 로 내려주는 에러 코드.
 *
 * 서버는 { isSuccess, code, message, result } 로 실패를 알리고, 각 api 모듈의
 * unwrapError 가 그 본문을 그대로 던진다. 그래서 화면은 e.code 로 사유를 분기한다.
 *
 * 값은 백엔드 enum 과 1:1 이다 —
 *   GeneralErrorCode (COMMON*), GroupErrorCode (GROUP*), AuthErrorCode (AUTH*),
 *   ProjectErrorCode (PROJECT*), BlockErrorCode (BLOCK*).
 * "GROUP410" 같은 값이 화면 로직에 흩어지면 무슨 상황인지 읽을 수 없으므로
 * 여기서 한 번 이름을 붙이고, 백엔드 enum 이 바뀌면 이 파일만 고친다.
 */
export const ERROR_CODE = {
  // 공통
  BAD_REQUEST: "COMMON400",
  VALIDATION_FAILED: "COMMON400_1", // @Valid 실패. result 에 필드별 메시지가 담긴다
  INVALID_TYPE: "COMMON400_2",
  MISSING_PARAMETER: "COMMON400_3",
  UNREADABLE_MESSAGE: "COMMON400_4", // JSON 파싱·enum 변환 실패
  UNAUTHORIZED: "COMMON401",
  FORBIDDEN: "COMMON403",
  NOT_FOUND: "COMMON404",
  METHOD_NOT_ALLOWED: "COMMON405",
  INTERNAL_SERVER_ERROR: "COMMON500",

  // 그룹
  GROUP_NAME_MISMATCH: "GROUP400",
  NOT_GROUP_MEMBER: "GROUP403",
  GROUP_NOT_FOUND: "GROUP404",
  INVITE_CODE_NOT_FOUND: "GROUP404_1",
  ALREADY_GROUP_MEMBER: "GROUP409",
  GROUP_FULL: "GROUP409_1",
  INVITE_CODE_EXPIRED: "GROUP410",
  INVITE_CODE_ISSUE_FAILED: "GROUP500",

  // 인증
  INVALID_TOKEN: "AUTH401_1",
  EXPIRED_TOKEN: "AUTH401_2",
  INVALID_REFRESH_TOKEN: "AUTH401_3",
  KAKAO_AUTH_FAILED: "AUTH401_4", // 인가코드 만료·재사용 — 재로그인으로 해결
  KAKAO_TOKEN_FAILED: "AUTH502_1",
  KAKAO_USER_INFO_FAILED: "AUTH502_2",

  // 프로젝트
  INVALID_DATE_RANGE: "PROJECT400", // 종료일 < 시작일
  PROJECT_NOT_FOUND: "PROJECT404",

  // 블록 — 대시보드 백엔드 브랜치에서 전달받은 값(2026-07-31).
  // 판정 순서 규약: 404 가 403 보다 먼저다 — 없는 블록은 비멤버에게도 404 (존재 여부 은닉).
  // ⚠️ BLOCK400 계열의 세부 순서(어느 _N 이 어느 상황인지)는 머지 후 BlockErrorCode 와 대조할 것.
  BLOCK_COORD_REQUIRED: "BLOCK400", // 장소성 카테고리(SPOT·FOOD·STAY) lat/lng 누락
  BLOCK_VEHICLE_FLAG_INVALID: "BLOCK400_1", // vehicleFlag 를 ETC 외 카테고리에 지정
  BLOCK_FIELD_UNSUPPORTED: "BLOCK400_2", // LWW 갱신 대상이 아닌 필드
  BLOCK_VALUE_INVALID: "BLOCK400_3", // 값 형식 오류
  BLOCK_NOT_FOUND: "BLOCK404",
  BLOCK_LOCK_NOT_HELD: "BLOCK409", // detail-lock 미보유 상태의 하트비트
  BLOCK_TOMBSTONED: "BLOCK410", // 삭제된 블록에 대한 지연 op
};
