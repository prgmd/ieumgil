import { useEffect } from 'react';

// 프로토타입의 .ov/.md 패턴을 그대로 씀. open이 false면 아예 렌더하지 않아
// display:none 토글보다 상태 관리가 단순해진다.
export default function Modal({ open, onClose, children, dismissible = true }) {
  useEffect(() => {
    if (!open) return;
    function onKey(e) {
      if (e.key === 'Escape' && dismissible) onClose();
    }
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose, dismissible]);

  if (!open) return null;

  return (
    <div
      className="ov open"
      onClick={(e) => {
        if (e.target === e.currentTarget && dismissible) onClose();
      }}
    >
      <div className="md">{children}</div>
    </div>
  );
}
