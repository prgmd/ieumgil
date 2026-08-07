import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import "./select.css";

/**
 * 크림 테마 커스텀 셀렉트. 네이티브 <select> 는 펼친 옵션 목록을 CSS 로 못 꾸미므로,
 * 트리거 버튼 + 포털 팝업 목록으로 직접 그린다(DatePicker 와 같은 방식). 팝업은 body
 * 로 포털해 모달 overflow 클리핑을 피하고, 아래 공간이 좁으면 위로 연다.
 *
 * @param value    현재 선택 값
 * @param onChange (value) => void
 * @param options  [{ value, label }]
 */
export function Select({
  value,
  onChange,
  options,
  placeholder = "선택",
  disabled = false,
}) {
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState(null);
  const wrapRef = useRef(null);
  const triggerRef = useRef(null);
  const popRef = useRef(null);

  useEffect(() => {
    if (!open) return;
    function onDown(e) {
      if (
        !wrapRef.current?.contains(e.target) &&
        !popRef.current?.contains(e.target)
      )
        setOpen(false);
    }
    function onKey(e) {
      if (e.key === "Escape") setOpen(false);
    }
    function onReflow() {
      setOpen(false);
    }
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    window.addEventListener("resize", onReflow);
    window.addEventListener("scroll", onReflow, true);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
      window.removeEventListener("resize", onReflow);
      window.removeEventListener("scroll", onReflow, true);
    };
  }, [open]);

  const selected = options.find((o) => o.value === value);

  function toggle() {
    if (disabled) return;
    if (!open && triggerRef.current) {
      const r = triggerRef.current.getBoundingClientRect();
      const listH = Math.min(options.length * 40 + 10, 280);
      const up = window.innerHeight - r.bottom < listH + 8;
      setPos({
        left: r.left,
        width: r.width,
        top: up ? undefined : r.bottom + 4,
        bottom: up ? window.innerHeight - r.top + 4 : undefined,
      });
    }
    setOpen((o) => !o);
  }

  return (
    <div className="sel-wrap" ref={wrapRef}>
      <button
        type="button"
        ref={triggerRef}
        className={`sel-trigger ${open ? "is-open" : ""}`}
        onClick={toggle}
        disabled={disabled}
      >
        <span className={selected ? "sel-value" : "sel-placeholder"}>
          {selected ? selected.label : placeholder}
        </span>
        <span className="sel-chevron" aria-hidden="true" />
      </button>
      {open &&
        pos &&
        createPortal(
          <ul
            className="sel-pop"
            ref={popRef}
            role="listbox"
            style={{
              position: "fixed",
              left: pos.left,
              width: pos.width,
              top: pos.top,
              bottom: pos.bottom,
              zIndex: 10000,
            }}
          >
            {options.map((o) => (
              <li key={o.value}>
                <button
                  type="button"
                  role="option"
                  aria-selected={o.value === value}
                  className={`sel-option ${o.value === value ? "is-sel" : ""}`}
                  onClick={() => {
                    onChange(o.value);
                    setOpen(false);
                  }}
                >
                  {o.label}
                </button>
              </li>
            ))}
          </ul>,
          document.body,
        )}
    </div>
  );
}
