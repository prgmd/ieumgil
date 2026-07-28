import { useState } from 'react';
import ConfirmModal from '../../My/shared/ui/ConfirmModal';
import { useGroupStore } from '../../My/shared/stores/groupStore';
import { useToastStore } from '../../My/shared/stores/toastStore';

export default function DeleteProjectModal({ open, onClose, project }) {
  const deleteProject = useGroupStore((s) => s.deleteProject);
  const showToast = useToastStore((s) => s.show);
  const [submitting, setSubmitting] = useState(false);

  if (!project) return null;

  const done = project.status === 'DONE';

  async function handleConfirm() {
    setSubmitting(true);
    try {
      await deleteProject(project.id);
      showToast('프로젝트를 삭제했어요');
      onClose();
    } catch {
      showToast('삭제에 실패했어요. 잠시 후 다시 시도해주세요.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <ConfirmModal
      open={open}
      onClose={onClose}
      title={`"${project.name}" 프로젝트를 삭제할까요?`}
      description={
        done
          ? '완료된 여행이에요. 삭제하면 대시보드의 기록도 함께 사라지고 되돌릴 수 없어요.'
          : '삭제하면 대시보드의 일정·블록이 모두 사라지고 되돌릴 수 없어요.'
      }
      confirmLabel="삭제"
      danger
      submitting={submitting}
      onConfirm={handleConfirm}
    />
  );
}
