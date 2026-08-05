/**
 * 주요 이동수단 선택 — 생성·수정 모달이 함께 쓴다.
 *
 * 예전에는 두 모달이 각자 <fieldset> + 브라우저 기본 체크박스를 그렸다. 모달의
 * 다른 입력(.md input — 1.5px 테두리·11px 라운드·흰 배경)과 생김새가 따로 놀았고,
 * 같은 UI 를 두 곳에서 따로 고쳐야 했다. 여기로 모아 칩 토글 하나로 통일한다.
 */

import { TRANSPORT_LABEL, TRANSPORT_ICON } from "./transportLabels";

/**
 * @param {string[]} value           선택된 값들 (["CAR", ...])
 * @param {(next: string[]) => void} onChange
 * @param {string}  [label]          기본 "주요 이동수단 *"
 */
export default function TransportPicker({
  value = [],
  onChange,
  label = "주요 이동수단 *",
}) {
  const toggle = (key) =>
    onChange(
      value.includes(key) ? value.filter((v) => v !== key) : [...value, key],
    );

  return (
    <>
      <label>
        {label} <span className="tp-hint">복수 선택</span>
      </label>
      <div className="tp-row" role="group" aria-label="주요 이동수단">
        {Object.entries(TRANSPORT_LABEL).map(([key, text]) => {
          const on = value.includes(key);
          return (
            <button
              key={key}
              type="button"
              className={`tp-chip ${on ? "on" : ""}`}
              // 체크박스가 아니라 버튼이므로 선택 상태를 aria 로 알린다
              aria-pressed={on}
              onClick={() => toggle(key)}
            >
              <span className="tp-ico" aria-hidden="true">
                {TRANSPORT_ICON[key]}
              </span>
              {text}
            </button>
          );
        })}
      </div>
    </>
  );
}
