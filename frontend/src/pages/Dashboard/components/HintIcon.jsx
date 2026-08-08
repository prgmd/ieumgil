import { useState, useCallback } from 'react';

// 안내 ⓘ 아이콘 + 앱 톤 말풍선(CSS ::after, data-tip). 기본은 위로 열지만
// 아이콘 위 공간이 말풍선 높이보다 모자라면(화면 상단 근처·긴 문구) 아래로 뒤집는다.
// 문구 길이로 높이를 추정해 짧은 팁은 그대로 위, 긴 팁만 필요할 때 내린다.
export function HintIcon({ tip, label }) {
  const [down, setDown] = useState(false);

  // 말풍선 폭 250px·12px 폰트 기준 대략 줄당 18자로 줄 수를 어림하고,
  // 줄높이(≈19px)+패딩/꼬리(≈32px)로 높이를 추정한다.
  // 화면 상단 sticky 앱바(높이 50px, base.css)가 뷰포트 최상단을 덮으므로
  // "위 여유 공간"은 뷰포트 top 0 이 아니라 앱바 아래부터다 — 안 빼면 스크롤
  // 뒤 위로 연 말풍선이 앱바에 가려 첫 줄이 잘린다.
  const APPBAR_H = 58;
  const decide = useCallback(
    (e) => {
      const rect = e.currentTarget.getBoundingClientRect();
      const lines = Math.max(1, Math.ceil((tip?.length || 0) / 18));
      const estHeight = lines * 19 + 32;
      setDown(rect.top - APPBAR_H < estHeight);
    },
    [tip],
  );

  return (
    <span
      className={`hint-ico${down ? ' tip-down' : ''}`}
      tabIndex={0}
      aria-label={label}
      data-tip={tip}
      onMouseEnter={decide}
      onFocus={decide}
    >
      ⓘ
    </span>
  );
}
