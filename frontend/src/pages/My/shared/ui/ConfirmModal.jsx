import Modal from './Modal';

/**
 * 단순 "확인/취소" 류 모달의 공통 뼈대. 프로젝트 삭제처럼
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
    <Modal open={open} onClose={onClose} closeOnBackdrop>
      <h3>{title}</h3>
      {description && <p className="s">{description}</p>}
      {note && <div className="note">{note}</div>}
      <div className="foot">
        <button className="btn btn-gh" onClick={onClose}>
          {cancelLabel}
        </button>
        <button
          className={`btn ${danger ? 'btn-danger' : 'btn-acc'}`}
          onClick={onConfirm}
          disabled={submitting}
        >
          {submitting ? '처리 중…' : confirmLabel}
        </button>
      </div>
    </Modal>
  );
}
