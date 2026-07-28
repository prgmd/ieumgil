import { useState } from 'react';
import Modal from '../shared/ui/Modal';
import { useToastStore } from '../../../global/stores/toastStore';

/**
 * @param onDelete (groupId, typedName) => Promise<void> — 목록을 소유한 페이지가 내려준다.
 */
export default function DeleteGroupModal({ open, onClose, group, onDelete }) {
  const showToast = useToastStore((s) => s.show);

  const [typed, setTyped] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  function handleClose() {
    setTyped('');
    setError('');
    setSubmitting(false);
    onClose();
  }

  async function handleDelete() {
    setError('');
    setSubmitting(true);
    try {
      await onDelete(group.id, typed);
      showToast('소프트 삭제 — 30일 후 완전 삭제');
      handleClose();
    } catch (e) {
      setSubmitting(false);
      if (e.code === 'NAME_MISMATCH') {
        setError('입력한 이름이 그룹명과 일치하지 않아요.');
      } else {
        setError('삭제에 실패했어요. 잠시 후 다시 시도해주세요.');
      }
    }
  }

  if (!group) return null;

  return (
    <Modal open={open} onClose={handleClose}>
      <h3>"{group.name}" 그룹을 삭제할까요?</h3>
      <p className="s">
        멤버 전원이 그룹에서 나가게 되고, 30일 후 완전히 삭제됩니다. 되돌리려면 이
        기간 안에 팀에 문의해야 해요.
      </p>
      <label>확인을 위해 그룹명을 입력하세요</label>
      <input
        placeholder={group.name}
        value={typed}
        onChange={(e) => setTyped(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && handleDelete()}
        autoFocus
      />
      {error && <div className="code-err">{error}</div>}
      <div className="foot">
        <button className="btn btn-gh" onClick={handleClose}>
          취소
        </button>
        <button
          className="btn btn-acc"
          style={{ background: '#9c3b3b' }}
          onClick={handleDelete}
          disabled={submitting || typed.length === 0}
        >
          {submitting ? '삭제 중…' : '삭제'}
        </button>
      </div>
    </Modal>
  );
}
