// 초대 코드를 클립보드에 복사하고 결과를 토스트로 알린다.
// 그룹 페이지·그룹 생성 모달이 같은 문구로 쓰던 것을 한곳에 모은다.
// navigator.clipboard 는 보안 컨텍스트(HTTPS/localhost)에서만 되므로 실패 시 안내한다.
export async function copyInviteCode(code, showToast) {
  try {
    await navigator.clipboard.writeText(code);
    showToast('초대 코드가 복사됐어요 🔗');
  } catch {
    showToast('복사에 실패했어요 — 코드를 직접 선택해 복사해주세요.');
  }
}
