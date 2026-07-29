import { useState } from 'react';
import Modal from '../shared/ui/Modal';
import { useToastStore } from '../../../global/stores/toastStore';

/**
 * MY-02: 그룹명(2~20자) 입력 → 생성 → 생성 직후 초대코드 공유 모달로 이어짐.
 * 이 두 단계를 하나의 컴포넌트에서 step으로 관리한다 — 사용자 입장에서는
 * "만들었더니 곧바로 코드가 뜨는" 하나의 흐름이라 모달을 분리할 이유가 없다.
 *
 * @param onCreate (name) => Promise<group> — 목록을 소유한 페이지가 내려준다.
 */
export default function CreateGroupModal({ open, onClose, onCreate }) {
  const showToast = useToastStore((s) => s.show);

  const [step, setStep] = useState('form'); // 'form' | 'share'
  const [name, setName] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [createdGroup, setCreatedGroup] = useState(null);

  function reset() {
    setStep('form');
    setName('');
    setError('');
    setSubmitting(false);
    setCreatedGroup(null);
  }

  function handleClose() {
    reset();
    onClose();
  }

  async function handleSubmit() {
    const trimmed = name.trim();
    if (trimmed.length < 2 || trimmed.length > 20) {
      setError('그룹명은 2~20자로 입력해주세요.');
      return;
    }
    setError('');
    setSubmitting(true);
    try {
      console.log(trimmed)
      const group = await onCreate(trimmed)
      setCreatedGroup(group);
      setStep('share');
    } catch {
      setError('그룹을 만들지 못했어요. 잠시 후 다시 시도해주세요.');
    } finally {
      setSubmitting(false);
    }
  }

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(createdGroup.inviteCode);
      showToast('초대 코드가 복사됐어요 🔗');
    } catch {
      showToast('복사에 실패했어요 — 코드를 직접 선택해 복사해주세요.');
    }
  }

  return (
    <Modal open={open} onClose={handleClose} dismissible={step === 'form'}>
      {step === 'form' && (
        <>
          <h3>새 그룹 만들기</h3>
          <p className="s">함께 여행할 사람들의 모임을 만들어요. 멤버 모두가 동등한 권한을 가집니다.</p>
          <label>그룹 이름 *</label>
          <input
            placeholder="예: A107 친구들"
            maxLength={20}
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleSubmit()}
            autoFocus
          />
          {error && <div className="code-err">{error}</div>}
          <div className="foot">
            <button className="btn btn-gh" onClick={handleClose}>
              취소
            </button>
            <button className="btn btn-acc" onClick={handleSubmit} disabled={submitting}>
              {submitting ? '만드는 중…' : '만들기'}
            </button>
          </div>
        </>
      )}

      {step === 'share' && createdGroup && (
        <>
          <h3>"{createdGroup.name}" 생성 완료</h3>
          <p className="s">
            아래 초대 코드를 친구에게 공유하세요. 코드는 발급 후 7일간 유효합니다.
          </p>
          <div className="code-chip">
            <span>{createdGroup.inviteCode}</span>
          </div>
          <div className="foot">
            <button className="btn btn-gh" onClick={handleCopy}>
              코드 복사
            </button>
            <button className="btn btn-acc" onClick={handleClose}>
              확인
            </button>
          </div>
        </>
      )}
    </Modal>
  );
}
