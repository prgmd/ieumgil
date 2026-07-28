import Modal from './Modal';

/**
 * 단순 "확인/취소" 류 모달의 공통 뼈대. 타이핑 확인이 필요한 그룹삭제는
 * 별도 컴포넌트(DeleteGroupModal)를 쓰고, 이건 방출/나가기처럼
 * "설명 읽고 확인 버튼 누르면 바로 실행"인 케이스에 쓴다.
 */
export default function ConfirmModal({
  open,
  onClose,
  title,
  description,
  note,
  confirmLabel = '확인',
  cancelLabel = '취소',
  danger = false,
  onConfirm,
  submitting = false,
}) {
  return (
    <Modal open={open} onClose={onClose}>
      <h3>{title}</h3>
      {description && <p className="s">{description}</p>}
      {note && <div className="note">{note}</div>}
      <div className="foot">
        <button className="btn btn-gh" onClick={onClose}>
          {cancelLabel}
        </button>
        <button
          className="btn btn-acc"
          style={danger ? { background: '#9c3b3b' } : undefined}
          onClick={onConfirm}
          disabled={submitting}
        >
          {submitting ? '처리 중…' : confirmLabel}
        </button>
      </div>
    </Modal>
  );
}
