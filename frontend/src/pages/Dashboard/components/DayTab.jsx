import React from "react";
import { hueOf } from "./memberColor";

/**
 * 다이어리 책갈피 모양의 Day 탭.
 * 모양(리본 노치·보드 아래로 끼워지는 느낌)은 CSS(.day-tab)에서 만들고 여기서는
 * 책갈피에 적히는 세 줄 — Day 번호 / 날짜 / 블록 수 — 만 담는다.
 */
export const DayTab = React.forwardRef(function DayTab(
  { label, date, count, isActive, onClick, viewers = [] },
  ref,
) {
  return (
    <button
      ref={ref}
      className={`day-tab ${isActive ? "on" : ""}`}
      onClick={onClick}
      aria-current={isActive ? "true" : undefined}
    >
      <span className="dt-top">
        <span className="dt-label">{label}</span>
        <span className="cnt">{count}</span>
      </span>
      {date && <span className="dt-date">{date}</span>}
      {/* 이 Day 를 지금 보고 있는 멤버들 — 프로필 아바타, 테두리는 커서와 같은
          멤버 색 (7단계). 이미지가 없으면 닉네임 첫 글자로 대신한다 */}
      {viewers.length > 0 && (
        <span
          className="dt-viewers"
          title={`보는 중: ${viewers.map((v) => v.name).join(", ")}`}
        >
          {viewers.slice(0, 2).map((v) =>
            v.profileImg?.startsWith("http") ? (
              <img
                key={v.id}
                src={v.profileImg}
                alt={v.name}
                style={{ "--vh": hueOf(v.id) }}
              />
            ) : (
              <i key={v.id} style={{ "--vh": hueOf(v.id) }}>
                {v.name[0]}
              </i>
            ),
          )}
          {viewers.length > 2 && (
            <em className="dt-viewers-more">+{viewers.length - 2}</em>
          )}
        </span>
      )}
    </button>
  );
});
