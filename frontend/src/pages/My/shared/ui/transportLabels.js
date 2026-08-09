/**
 * PROJECT.transport_pref 표기 — ERD 상 CAR | PUBLIC 두 값뿐이고 한글은 표시용이다.
 *
 * 생성 모달·수정 모달·프로젝트 카드가 각자 같은 표를 복사해 두고 있었다. 한 곳에서
 * 라벨을 바꾸면 다른 곳이 따라오지 않아 "자차"와 "자차 (렌트)"가 섞이던 원인이다.
 * (컴포넌트 파일이 아니라 여기에 두는 이유: 상수를 컴포넌트와 같이 export 하면
 *  react-refresh 가 Fast Refresh 를 포기한다.)
 */
export const TRANSPORT_LABEL = { CAR: "자차 (렌트)", PUBLIC: "대중교통" };
export const TRANSPORT_ICON = { CAR: "🚗", PUBLIC: "🚌" };
