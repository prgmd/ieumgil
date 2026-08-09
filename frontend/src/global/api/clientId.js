/**
 * 브라우저 탭 식별자 (X-Client-Id 헤더 값).
 *
 * 변경 요청에 이 값을 실어 보내면, 서버가 브로드캐스트 op 에 clientId 로 되돌려주고
 * 요청자 본인은 그걸 보고 자기 op 를 스킵한다 — 낙관적 UI 로 이미 반영한 변경을
 * op 로 또 적용하는 이중 적용을 막기 위함(dashboard-api.md 공통 규약).
 *
 * sessionStorage 를 쓰는 이유 — localStorage 면 모든 탭이 같은 id 를 공유해서,
 * 같은 브라우저의 두 탭으로 동시 편집을 테스트할 때 서로의 op 까지 스킵해버린다.
 * sessionStorage 는 탭마다 독립이고 새로고침에는 유지되므로 "탭 UUID" 에 정확히 맞는다.
 */

const KEY = "ieumgil.clientId";

export function getClientId() {
  let id = sessionStorage.getItem(KEY);
  if (!id) {
    id = crypto.randomUUID();
    sessionStorage.setItem(KEY, id);
  }
  return id;
}
