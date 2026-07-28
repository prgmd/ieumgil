import { useState } from 'react';
import ConfirmModal from '../../My/shared/ui/ConfirmModal';
import { useGroupStore } from '../../My/shared/stores/groupStore';
import { useToastStore } from '../../My/shared/stores/toastStore';

export default function KickMemberModal({ open, onClose, groupId, member }) {
  const kickMember = useGroupStore((s) => s.kickMember);
  const showToast = useToastStore((s) => s.show);
  const [submitting, setSubmitting] = useState(false);

  async function handleConfirm() {
    setSubmitting(true);
    try {
      await kickMember(groupId, member.userId);
      showToast(`${member.nickname}님을 방출했어요 — 재입장은 코드 재발급 필요`);
      onClose();
    } finally {
      setSubmitting(false);
    }
  }

  if (!member) return null;

  return (
    <ConfirmModal
      open={open}
      onClose={onClose}
      title={`${member.nickname}님을 방출할까요?`}
      description="즉시 그룹 접근이 차단됩니다. 작성한 블록은 유지되며 작성자 표기도 그대로 남아요."
      note="방출된 멤버는 동일한 초대 코드로 재입장할 수 없어요 — 재입장하려면 코드를 재발급해야 합니다."
      confirmLabel="방출"
      danger
      submitting={submitting}
      onConfirm={handleConfirm}
    />
  );
}
