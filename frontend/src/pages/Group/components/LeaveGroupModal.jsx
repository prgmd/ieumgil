import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Modal from '../../My/shared/ui/Modal';
import { useAuthStore } from '../../My/shared/stores/authStore';
import { useGroupStore } from '../../My/shared/stores/groupStore';
import { useToastStore } from '../../My/shared/stores/toastStore';

export default function LeaveGroupModal({ open, onClose, group }) {
  const currentUser = useAuthStore((s) => s.currentUser);
  const leaveGroup = useGroupStore((s) => s.leaveGroup);
  const showToast = useToastStore((s) => s.show);
  const navigate = useNavigate();
  const [submitting, setSubmitting] = useState(false);

  if (!group) return null;

  const isOwner = group.ownerId === currentUser.id;
  const others = group.members.filter((m) => m.userId !== currentUser.id);
  // 위임 대상 미리보기: 가입일이 가장 오래된 멤버 (02장, GRP-09)
  const nextOwner = isOwner
    ? [...others].sort((a, b) => new Date(a.joinedAt) - new Date(b.joinedAt))[0]
    : null;
  const isLastMember = others.length === 0;

  async function handleLeave() {
    setSubmitting(true);
    try {
      const result = await leaveGroup(group.id, currentUser.id);
      if (result.softDeleted) {
        showToast('마지막 멤버였어요 — 그룹이 소프트 삭제됐어요');
      } else if (result.newOwnerId) {
        showToast('그룹에서 나갔어요 — 방장 권한이 위임됐어요');
      } else {
        showToast('그룹에서 나갔어요');
      }
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
      {isOwner && isLastMember && (
        <div className="note">
          마지막 남은 멤버예요 — 나가면 위임할 대상이 없어 그룹이 소프트
          삭제됩니다(30일 후 완전 삭제).
        </div>
      )}
      {isOwner && !isLastMember && nextOwner && (
        <div className="note">
          {currentUser.nickname}님은 방장이에요 — 나가면 방장 권한이 가입일이
          가장 오래된 멤버 <b>{nextOwner.nickname}</b>님에게 자동 위임됩니다.
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
