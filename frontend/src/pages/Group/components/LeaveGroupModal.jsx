import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Modal from '../../My/shared/ui/Modal';
import { useAuthStore } from '../../../global/stores/authStore';
import { useToastStore } from '../../../global/stores/toastStore';

/**
 * @param onLeave () => Promise<{ groupDeleted }> — 그룹을 소유한 페이지가 내려준다.
 */
export default function LeaveGroupModal({ open, onClose, group, onLeave }) {
  const currentUser = useAuthStore((s) => s.currentUser);
  const showToast = useToastStore((s) => s.show);
  const navigate = useNavigate();
  const [submitting, setSubmitting] = useState(false);

  if (!group) return null;

  // flat 모델 — 방장/승계가 없고, 마지막 1인이 나가면 그룹이 하드 삭제된다(GRP-09).
  const isLastMember = group.members.every((m) => m.userId === currentUser.id);

  async function handleLeave() {
    setSubmitting(true);
    try {
      const result = await onLeave();
      showToast(
        result.groupDeleted
          ? '마지막 멤버였어요 — 그룹이 완전히 삭제됐어요'
          : '그룹에서 나갔어요'
      );
      navigate('/my');
    } finally {
      setSubmitting(false);
      onClose();
    }
  }

  return (
    <Modal open={open} onClose={onClose}>
      <h3>그룹을 나갈까요?</h3>
      <p className="s">
        작성한 블록·기록은 그룹 자산으로 유지되고, 작성자는 "탈퇴한 멤버"로
        표시됩니다. 나중에 유효한 초대 코드로 다시 입장할 수 있어요.
      </p>
      {isLastMember && (
        <div className="note">
          마지막 남은 멤버예요 — 나가면 그룹이 <b>즉시 완전 삭제</b>됩니다.
          프로젝트와 블록도 함께 사라지고 되돌릴 수 없어요.
        </div>
      )}
      <div className="foot">
        <button className="btn btn-gh" onClick={onClose}>
          취소
        </button>
        <button
          className="btn btn-acc"
          style={{ background: '#9c3b3b' }}
          onClick={handleLeave}
          disabled={submitting}
        >
          {submitting ? '나가는 중…' : '나가기'}
        </button>
      </div>
    </Modal>
  );
}
