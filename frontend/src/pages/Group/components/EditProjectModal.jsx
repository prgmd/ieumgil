import { useState } from 'react';
import Modal from '../../My/shared/ui/Modal';
import TransportPicker from '../../My/shared/ui/TransportPicker';
import { DatePicker } from '../../../global/components/DatePicker';
import { useToastStore } from '../../../global/stores/toastStore';

/**
 * 프로젝트 수정 모달 (PRJ-02).
 *
 * 이름·기간·목적지·이동수단은 PATCH /projects/{id} 로, 여행 인원은 전용 엔드포인트
 * PATCH /projects/{id}/budget-headcount 로 저장된다 — 두 요청을 useProjects.updateProject
 * 가 한 번에 묶어 보내므로 이 화면은 폼 하나만 다룬다.
 *
 * @param onUpdate (projectId, form) => Promise<updated> — 목록을 소유한 페이지가 내려준다.
 * @param onSaved  (선택) 저장 성공 후 호출 — 대시보드처럼 자체 스냅샷을 다시 읽어야
 *                 하는 화면이 쓴다.
 *
 * 폼 초기값은 마운트 시점의 project 로 한 번만 잡는다. 호출부가 수정할 프로젝트가
 * 있을 때만 이 컴포넌트를 렌더하므로, 다른 카드의 ✎ 를 누르면 새로 마운트되면서
 * 그 프로젝트의 값으로 다시 채워진다 — 동기화 effect 가 필요 없다.
 */
export default function EditProjectModal({
  open,
  onClose,
  project,
  onUpdate,
  onSaved,
}) {
  const showToast = useToastStore((s) => s.show);

  const [form, setForm] = useState({
    name: project?.name ?? '',
    startDate: project?.startDate ?? '',
    endDate: project?.endDate ?? '',
    destination: project?.destination ?? '',
    budgetHeadcount: project?.budgetHeadcount ?? 1,
    transportPrefs: project?.transportPrefs ?? [],
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
    if (!form.budgetHeadcount || Number(form.budgetHeadcount) < 1) {
      setError('여행 인원은 1명 이상이어야 해요.');
      return;
    }
    if (form.transportPrefs.length < 1) {
      setError('주요 이동수단을 선택해주세요.');
      return;
    }

    setError('');
    setSubmitting(true);
    try {
      await onUpdate(project.projectId, form);
      showToast('프로젝트가 수정됐어요 ✓');
      onSaved?.();
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
          <DatePicker
            value={form.startDate}
            onChange={(v) => update('startDate', v)}
          />
        </div>
        <div>
          <label>여행 종료일 *</label>
          <DatePicker
            value={form.endDate}
            min={form.startDate}
            onChange={(v) => update('endDate', v)}
          />
        </div>
      </div>

      <div className="r2">
        <div>
          <label>목적지</label>
          <input
            placeholder="예: 부산"
            maxLength={100}
            value={form.destination}
            onChange={(e) => update('destination', e.target.value)}
          />
        </div>
        <div>
          <label>여행 인원 *</label>
          <input
            type="number"
            min={1}
            value={form.budgetHeadcount}
            onChange={(e) => update('budgetHeadcount', e.target.value)}
          />
        </div>
      </div>

      <TransportPicker
        value={form.transportPrefs}
        onChange={(next) => update('transportPrefs', next)}
      />

      <div className="note">
        여행 인원은 정산(1인당 금액) 기준이에요 — 바꾸면 대시보드 예산 표시도 함께
        달라져요.
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
