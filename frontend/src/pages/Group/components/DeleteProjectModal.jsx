import { useState } from 'react';
import ConfirmModal from '../../My/shared/ui/ConfirmModal';
import { isTripFinished } from '../../../features/group/util/tripStatus';
import { useToastStore } from '../../../global/stores/toastStore';

/**
 * @param onDelete (projectId) => Promise<void> — 목록을 소유한 페이지가 내려준다.
 */
export default function DeleteProjectModal({ open, onClose, project, onDelete }) {
  const showToast = useToastStore((s) => s.show);
  const [submitting, setSubmitting] = useState(false);

  if (!project) return null;

  // 카드의 배지와 같은 판정을 써야 한다 — 카드엔 "여행 완료"인데 삭제 확인창은
  // 계획 중처럼 말하면 같은 프로젝트를 두 가지로 부르는 셈이다
  const done = isTripFinished(project);

  async function handleConfirm() {
    setSubmitting(true);
    try {
      await onDelete(project.projectId);
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
