import { useEffect } from 'react';

// 프로토타입의 .ov/.md 패턴을 그대로 씀. open이 false면 아예 렌더하지 않아
// display:none 토글보다 상태 관리가 단순해진다.
//
// 닫기 정책은 둘로 나눈다 — 예전엔 dismissible 하나가 백드롭과 Esc를 함께 쥐고
// 있어, 입력 폼 모달도 밖을 실수로 누르면 통째로 날아갔다.
//   closeOnBackdrop: 바깥(오버레이) 클릭으로 닫힘. 기본 false — 입력 유실 방지가
//                    기본값이다. 잃을 것 없는 확인성 모달만 true 로 연다.
//   closeOnEsc:      Esc 로 닫힘. 기본 true.
//
// 대시보드처럼 자체 오버레이/박스 스타일을 가진 모달도 이 한 컴포넌트로 열기·Esc·
// 백드롭 정책을 공유하도록, 겉모습은 아래 두 옵션으로 위임한다.
//   overlayClassName: 오버레이 클래스를 갈아끼운다(기본 'ov'). 지정 시 그 클래스에
//                     'open'만 덧붙는다.
//   bodyless:         true면 기본 .md 박스를 두르지 않고 children을 오버레이 직속으로
//                     둔다 — children이 자기 박스를 직접 그릴 때.
//   hidden:           open은 유지한 채(언마운트 없이) 'is-hidden'만 붙여 감춘다.
//                     지도 핀 지정처럼 잠시 감췄다 되돌릴 때 폼 state를 살려 둔다.
export default function Modal({
  open,
  onClose,
  children,
  closeOnBackdrop = false,
  closeOnEsc = true,
  overlayClassName,
  bodyless = false,
  hidden = false,
}) {
  useEffect(() => {
    if (!open || hidden || !closeOnEsc) return;
    function onKey(e) {
      if (e.key === 'Escape') onClose();
    }
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, hidden, onClose, closeOnEsc]);

  if (!open) return null;

  const overlayCls = [overlayClassName ?? 'ov', 'open', hidden ? 'is-hidden' : '']
    .filter(Boolean)
    .join(' ');

  return (
    <div
      className={overlayCls}
      onClick={(e) => {
        if (e.target === e.currentTarget && closeOnBackdrop) onClose();
      }}
    >
      {bodyless ? children : <div className="md">{children}</div>}
    </div>
  );
}
