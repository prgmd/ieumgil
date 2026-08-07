import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { DayPicker } from "react-day-picker";
import { ko } from "react-day-picker/locale";
import "react-day-picker/style.css";
import "./datePicker.css";

// 프로젝트 폼은 날짜를 'YYYY-MM-DD' 문자열로 주고받는다. react-day-picker 는 Date
// 를 쓰므로 경계에서 변환한다. new Date('YYYY-MM-DD') 는 UTC 자정으로 읽혀 KST 에서
// 하루 밀리므로, 로컬 생성/포맷으로 그 함정을 피한다.
function isoToDate(iso) {
  if (!iso || iso.length < 10) return undefined;
  const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
  return new Date(y, m - 1, d);
}
function dateToIso(date) {
  if (!date) return "";
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];
function formatTrigger(iso) {
  const dt = isoToDate(iso);
  if (!dt) return "";
  return `${iso.slice(0, 4)}.${iso.slice(5, 7)}.${iso.slice(8, 10)} (${WEEKDAYS[dt.getDay()]})`;
}

/**
 * 크림 테마 날짜 선택 필드. 네이티브 <input type="date"> 를 대체한다 — 트리거를
 * 눌러 팝오버 달력을 열고, 하루를 고르면 닫힌다. 값은 'YYYY-MM-DD' 문자열로
 * 주고받아 기존 폼 로직을 그대로 쓴다.
 *
 * 팝오버는 body 로 포털한다 — 모달 .md 의 overflow:auto 가 안에 절대배치한 달력을
 * 잘라 버리기 때문이다. 트리거 사각형을 재 fixed 로 띄우고, 아래 공간이 좁으면
 * 위로 뒤집어 화면 밖으로 삐져나가지 않게 한다. 폭은 트리거(입력칸)에 맞춘다.
 *
 * @param value    'YYYY-MM-DD' | ''
 * @param onChange (iso) => void
 * @param min      선택 하한 'YYYY-MM-DD' (이전 날짜는 비활성)
 */
export function DatePicker({
  value,
  onChange,
  min,
  placeholder = "날짜 선택",
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
      const inWrap = wrapRef.current?.contains(e.target);
      const inPop = popRef.current?.contains(e.target);
      if (!inWrap && !inPop) setOpen(false);
    }
    function onKey(e) {
      if (e.key === "Escape") setOpen(false);
    }
    // 모달을 스크롤하거나 창 크기가 바뀌면 fixed 팝오버가 트리거에서 떨어지므로 닫는다.
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

  const selected = isoToDate(value);
  const minDate = isoToDate(min);

  function toggle() {
    if (disabled) return;
    if (!open && triggerRef.current) {
      const rect = triggerRef.current.getBoundingClientRect();
      // 아래 공간이 달력 높이(약 360px)보다 좁으면 위로 연다.
      const up = window.innerHeight - rect.bottom < 360;
      setPos({
        left: rect.left,
        width: rect.width,
        top: up ? undefined : rect.bottom + 4,
        bottom: up ? window.innerHeight - rect.top + 4 : undefined,
      });
    }
    setOpen((o) => !o);
  }

  return (
    <div className="dp-wrap" ref={wrapRef}>
      <button
        type="button"
        ref={triggerRef}
        className={`dp-trigger ${open ? "is-open" : ""}`}
        onClick={toggle}
        disabled={disabled}
      >
        <span className={value ? "dp-value" : "dp-placeholder"}>
          {value ? formatTrigger(value) : placeholder}
        </span>
        <span className="dp-ico" aria-hidden="true">
          📅
        </span>
      </button>
      {open &&
        pos &&
        createPortal(
          <div
            className="dp-pop"
            ref={popRef}
            style={{
              position: "fixed",
              left: pos.left,
              width: pos.width,
              top: pos.top,
              bottom: pos.bottom,
              zIndex: 10000,
            }}
          >
            <DayPicker
              mode="single"
              locale={ko}
              selected={selected}
              defaultMonth={selected ?? minDate}
              disabled={minDate ? { before: minDate } : undefined}
              onSelect={(d) => {
                if (!d) return;
                onChange(dateToIso(d));
                setOpen(false);
              }}
            />
          </div>,
          document.body,
        )}
    </div>
  );
}
