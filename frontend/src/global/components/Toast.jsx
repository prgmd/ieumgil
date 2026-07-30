import { useToastStore } from '../stores/toastStore';
import './toast.css';

/**
 * 전역 토스트 표시 컴포넌트.
 * showToast(...)는 toastStore의 상태(message/visible)만 바꾸는데,
 * 그 상태를 실제로 화면에 그려주는 쪽이 지금까지 없었다 — 그래서 showToast를
 * 아무리 호출해도 아무 것도 안 보였다. App.jsx에 한 번만 마운트해두면
 * 어느 페이지에서 showToast를 불러도 이 컴포넌트가 받아서 그린다.
 */
export function Toast() {
  const message = useToastStore((s) => s.message);
  const visible = useToastStore((s) => s.visible);

  return (
    <div className={`toast ${visible ? 'toast--visible' : ''}`} role="status" aria-live="polite">
      {message}
    </div>
  );
}
