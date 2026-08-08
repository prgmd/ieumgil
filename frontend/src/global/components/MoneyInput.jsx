import "./moneyInput.css";

/** 1만원 이상은 만원 단위 소수 첫째자리("1.5만원"), 미만은 원 콤마("5,000원"). */
function moneyHint(n) {
  if (!n || n <= 0) return "";
  if (n >= 10000) {
    const man = n / 10000;
    const text =
      man >= 100
        ? Math.round(man).toLocaleString("ko-KR")
        : man.toFixed(1).replace(/\.0$/, "");
    return `${text}만원`;
  }
  return `${n.toLocaleString("ko-KR")}원`;
}

/**
 * 공용 금액 입력 — 천단위 콤마 자동 포맷 + inputMode numeric(모바일 숫자 키패드).
 * hint=true 면 입력칸 '안' 오른쪽에 "≈ N만원" 환산을 겹쳐 보여준다(폭이 흔들리지 않게).
 *
 * value: 숫자 또는 디지트 문자열. onChange 는 콤마를 뗀 디지트 문자열(빈 값은 "")을 준다.
 * className: 필드에 그대로 붙인다(문맥별 입력 스타일 — 예: "bef-input").
 * hint=false 면 래퍼 없이 <input> 하나만 반환한다(인라인 스테퍼 등에 드롭인).
 */
export function MoneyInput({
  value,
  onChange,
  hint = true,
  className = "",
  name,
  placeholder,
  readOnly = false,
  autoFocus = false,
  onBlur,
  onKeyDown,
}) {
  const digits = String(value ?? "").replace(/[^\d]/g, "");
  const num = Number(digits);
  const display = num > 0 ? num.toLocaleString("ko-KR") : "";

  const field = (
    <input
      className={className}
      type="text"
      inputMode="numeric"
      name={name}
      value={display}
      placeholder={placeholder}
      readOnly={readOnly}
      autoFocus={autoFocus}
      onBlur={onBlur}
      onKeyDown={onKeyDown}
      onChange={(e) => onChange(e.target.value.replace(/[^\d]/g, ""))}
    />
  );
  if (!hint) return field;

  const h = moneyHint(num);
  return (
    <div className="money-input">
      {field}
      {h && <span className="money-input__hint">≈ {h}</span>}
    </div>
  );
}
