import { useState } from 'react';
import Modal from '../../My/shared/ui/Modal';
import { useGroupStore } from '../../My/shared/stores/groupStore';
import { useToastStore } from '../../My/shared/stores/toastStore';

const TRANSPORT_OPTIONS = ['KTX/SRT', '비행기', '고속버스', '지하철/버스', '자차'];

function todayISO() {
  return new Date().toISOString().slice(0, 10);
}

const initialForm = {
  name: '',
  destination: '',
  headcount: 4,
  startDate: todayISO(),
  endDate: todayISO(),
  transport: '', // 기본값 없음 — 사용자가 직접 골라야 함
};

export default function CreateProjectModal({ open, onClose, groupId }) {
  const createProject = useGroupStore((s) => s.createProject);
  const showToast = useToastStore((s) => s.show);

  const [form, setForm] = useState(initialForm);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  function update(field, value) {
    setForm((f) => ({ ...f, [field]: value }));
  }

  function reset() {
    setForm(initialForm);
    setError('');
    setSubmitting(false);
  }

  function handleClose() {
    reset();
    onClose();
  }

  async function handleSubmit() {
    if (!form.name.trim()) {
      setError('프로젝트 이름을 입력해주세요.');
      return;
    }
    if (!form.destination.trim()) {
      setError('목적지를 입력해주세요.');
      return;
    }
    if (!form.headcount || Number(form.headcount) < 1) {
      setError('여행 인원은 1명 이상이어야 해요.');
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
      await createProject(groupId, form);
      showToast('새 프로젝트가 생성됐어요 ✈');
      handleClose();
    } catch {
      setError('프로젝트를 만들지 못했어요. 잠시 후 다시 시도해주세요.');
      setSubmitting(false);
    }
  }

  return (
    <Modal open={open} onClose={handleClose}>
      <h3>새 여행 프로젝트</h3>
      <p className="s">기본 정보를 입력하면 챗봇 이음이가 이를 참고해 후보 블록을 준비해요.</p>

      <label>프로젝트 이름 *</label>
      <input
        placeholder="예: 가을 전주 미식 여행"
        maxLength={30}
        value={form.name}
        onChange={(e) => update('name', e.target.value)}
        autoFocus
      />

      <div className="r2">
        <div>
          <label>목적지 *</label>
          <input
            placeholder="예: 부산"
            value={form.destination}
            onChange={(e) => update('destination', e.target.value)}
          />
        </div>
        <div>
          <label>여행 인원 *</label>
          <input
            type="number"
            min={1}
            value={form.headcount}
            onChange={(e) => update('headcount', e.target.value)}
          />
        </div>
      </div>

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

      <label>주요 교통수단</label>
      <select value={form.transport} onChange={(e) => update('transport', e.target.value)}>
        <option value="">선택 안 함</option>
        {TRANSPORT_OPTIONS.map((t) => (
          <option key={t} value={t}>
            {t}
          </option>
        ))}
      </select>

      {error && <div className="code-err">{error}</div>}

      <div className="foot">
        <button className="btn btn-gh" onClick={handleClose}>
          취소
        </button>
        <button className="btn btn-acc" onClick={handleSubmit} disabled={submitting}>
          {submitting ? '만드는 중…' : '만들기'}
        </button>
      </div>
    </Modal>
  );
}
