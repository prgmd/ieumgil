import { useState } from 'react';
import Modal from '../shared/ui/Modal';

// 오타로 지나칠 수 없게 정확히 입력해야 하는 확인 문구 — 그룹 삭제 모달이
// 그룹명을 받는 것과 같은 장치다(되돌릴 수 없는 동작의 마지막 관문).
const CONFIRM_PHRASE = '탈퇴합니다';

/**
 * 회원 탈퇴 확인 모달.
 *
 * @param {number} soloGroupCount 나 혼자인 그룹 수 — 탈퇴하면 그 그룹은 서버가
 *        하드 삭제한다(프로젝트·블록까지). 사후 통보로는 늦어서 미리 숫자로 알린다.
 * @param onWithdraw () => Promise<void> — 성공 후 화면 이동까지 호출부가 맡는다.
 */
export default function WithdrawModal({
  open,
  onClose,
  soloGroupCount = 0,
  onWithdraw,
}) {
  const [typed, setTyped] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  function handleClose() {
    setTyped('');
    setError('');
    setSubmitting(false);
    onClose();
  }

  async function handleWithdraw() {
    if (typed.trim() !== CONFIRM_PHRASE || submitting) return;
    setError('');
    setSubmitting(true);
    try {
      await onWithdraw();
      // 성공하면 호출부가 화면을 떠나므로 여기서 닫지 않는다 — 먼저 닫으면
      // 이동 전 한 프레임 동안 빈 개인 페이지가 보인다.
    } catch (e) {
      setSubmitting(false);
      setError(e?.message ?? '탈퇴에 실패했어요. 잠시 후 다시 시도해주세요.');
    }
  }

  return (
    <Modal open={open} onClose={handleClose}>
      <h3>정말 탈퇴할까요?</h3>
      <p className="s">
        계정 정보가 파기되고 모든 그룹에서 나가게 됩니다. <b>되돌릴 수 없어요.</b>
      </p>

      <div className="note">
        {soloGroupCount > 0 ? (
          <>
            나 혼자 있는 그룹 <b>{soloGroupCount}개</b>는 여행 계획(프로젝트·블록)까지
            함께 삭제돼요. 남기고 싶다면 먼저 팀원을 초대해주세요.
          </>
        ) : (
          <>
            팀원이 있는 그룹은 그대로 남고, 내가 만든 블록도 지워지지 않아요 — 나만
            멤버에서 빠집니다.
          </>
        )}
      </div>

      <label>확인을 위해 "{CONFIRM_PHRASE}"를 입력하세요</label>
      <input
        placeholder={CONFIRM_PHRASE}
        value={typed}
        onChange={(e) => setTyped(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && handleWithdraw()}
        autoFocus
      />
      {error && <div className="code-err">{error}</div>}

      <div className="foot">
        <button className="btn btn-gh" onClick={handleClose}>
          취소
        </button>
        <button
          className="btn btn-acc"
          style={{ background: 'var(--danger)' }}
          onClick={handleWithdraw}
          disabled={submitting || typed.trim() !== CONFIRM_PHRASE}
        >
          {submitting ? '탈퇴 중…' : '탈퇴하기'}
        </button>
      </div>
    </Modal>
  );
}
