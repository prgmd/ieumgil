/**
 * 백엔드가 응답 본문의 `code` 로 내려주는 에러 코드.
 *
 * 서버는 { isSuccess, code, message, result } 로 실패를 알리고, 각 api 모듈의
 * unwrapError 가 그 본문을 그대로 던진다. 그래서 화면은 e.code 로 사유를 분기한다.
 *
 * 값은 백엔드 enum 과 1:1 이다 —
 *   GeneralErrorCode (COMMON*), GroupErrorCode (GROUP*), AuthErrorCode (AUTH*).
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
  KAKAO_TOKEN_FAILED: "AUTH502_1",
  KAKAO_USER_INFO_FAILED: "AUTH502_2",
};
