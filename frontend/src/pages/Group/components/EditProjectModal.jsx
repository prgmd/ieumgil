import { useState } from 'react';
import Modal from '../../My/shared/ui/Modal';
import { useToastStore } from '../../../global/stores/toastStore';

const TRANSPORT_LABEL = { CAR: '자차 (렌트)', PUBLIC: '대중교통' };

/**
 * 프로젝트 수정 모달 (PRJ-02).
 *
 * 수정 가능한 건 서버가 PATCH /projects/{id} 로 받는 이름·시작일·종료일 세 개다.
 * 목적지·인원·이동수단은 수정 API 가 없어 값만 보여주고 잠가둔다 — 편집되는 것처럼
 * 보이면 새로고침 때 되돌아가서 오히려 버그로 읽힌다.
 *
 * @param onUpdate (projectId, form) => Promise<updated> — 목록을 소유한 페이지가 내려준다.
 *
 * 폼 초기값은 마운트 시점의 project 로 한 번만 잡는다. 호출부(GroupPage)가 수정할
 * 프로젝트가 있을 때만 이 컴포넌트를 렌더하므로, 다른 카드의 ✎ 를 누르면 새로
 * 마운트되면서 그 프로젝트의 값으로 다시 채워진다 — 동기화 effect 가 필요 없다.
 */
export default function EditProjectModal({ open, onClose, project, onUpdate }) {
  const showToast = useToastStore((s) => s.show);

  const [form, setForm] = useState({
    name: project?.name ?? '',
    startDate: project?.startDate ?? '',
    endDate: project?.endDate ?? '',
  });
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  function update(field, value) {
    setForm((f) => ({ ...f, [field]: value }));
  }

  async function handleSubmit() {
    if (!form.name.trim()) {
      setError('프로젝트 이름을 입력해주세요.');
      return;
    }
    if (!form.startDate || !form.endDate) {
      setError('여행 시작일과 종료일을 선택해주세요.');
      return;
    }
    if (form.startDate > form.endDate) {
      setError('종료일은 시작일보다 빠를 수 없어요.');
      return;
    }

    setError('');
    setSubmitting(true);
    try {
      await onUpdate(project.projectId, form);
      showToast('프로젝트가 수정됐어요 ✓');
      onClose();
    } catch (e) {
      setError(e?.message ?? '수정에 실패했어요. 잠시 후 다시 시도해주세요.');
      setSubmitting(false);
    }
  }

  if (!project) return null;

  return (
    <Modal open={open} onClose={onClose}>
      <h3>프로젝트 수정</h3>
      <p className="s">
        이름과 기간을 바꾸면 대시보드의 제목·Day 탭·날짜에 그대로 반영돼요.
      </p>

      <label>프로젝트 이름 *</label>
      <input
        maxLength={30}
        value={form.name}
        onChange={(e) => update('name', e.target.value)}
        autoFocus
      />

      <div className="r2">
        <div>
          <label>여행 시작일 *</label>
          <input
            type="date"
            value={form.startDate}
            onChange={(e) => update('startDate', e.target.value)}
          />
        </div>
        <div>
          <label>여행 종료일 *</label>
          <input
            type="date"
            value={form.endDate}
            min={form.startDate}
            onChange={(e) => update('endDate', e.target.value)}
          />
        </div>
      </div>

      <div className="r2">
        <div>
          <label>목적지</label>
          <input value={project.destination ?? ''} disabled />
        </div>
        <div>
          <label>여행 인원</label>
          <input value={`${project.budgetHeadcount ?? '-'}인`} disabled />
        </div>
      </div>

      <label>주요 이동수단</label>
      <input value={TRANSPORT_LABEL[project.transportPref] ?? '-'} disabled />

      <div className="note">
        목적지·인원·이동수단은 아직 수정 API가 없어 잠겨 있어요. 바꿔야 하면 프로젝트를
        새로 만들어주세요.
      </div>

      {error && <div className="code-err">{error}</div>}

      <div className="foot">
        <button className="btn btn-gh" onClick={onClose}>
          취소
        </button>
        <button className="btn btn-acc" onClick={handleSubmit} disabled={submitting}>
          {submitting ? '저장 중…' : '저장'}
        </button>
      </div>
    </Modal>
  );
}
