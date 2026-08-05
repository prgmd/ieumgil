import { hueOf } from "./memberColor";
import { openBlockLink, hasExternalLink } from "../../../features/dashboard/api/externalLink";

/**
 * 블록의 수정 표시. 개인 페이지의 카드(.g-card .op)와 같은 ✎ 아이콘·같은 규칙 —
 * 평소에는 숨어 있고 카드에 마우스를 올릴 때만 나타난다(노출은 CSS .blk-op).
 *
 * 카드 전체에 드래그 리스너가 걸려 있어 pointerdown 을 여기서 끊어야 버튼을 누르는
 * 동작이 드래그 시작으로 오해되지 않는다.
 */
function BlockEditBadge({ onEdit }) {
  if (!onEdit) return null;
  return (
    <button
      type="button"
      className="blk-op"
      title="블록 수정"
      aria-label="블록 수정"
      onPointerDown={(e) => e.stopPropagation()}
      onClick={(e) => {
        e.stopPropagation();
        onEdit();
      }}
    >
      ✎
    </button>
  );
}

/**
 * 블록 출처(카카오/축제)로 이동하는 외부 링크 아이콘. hover 시에만 노출(.blk-op와 같은 규칙).
 * 카드 전체에 드래그 리스너가 걸려 있어 pointerdown 을 끊어 드래그 시작으로 오해되지 않게 한다.
 */
function BlockLinkBadge({ item }) {
  if (!hasExternalLink(item)) return null;
  return (
    <button
      type="button"
      className="blk-link"
      title="출처 링크 열기"
      aria-label="출처 링크 열기"
      onPointerDown={(e) => e.stopPropagation()}
      onClick={(e) => {
        e.stopPropagation();
        openBlockLink(item);
      }}
    >
      🔗
    </button>
  );
}

/** 블록 좌상단의 "가장 최근 수정자" 아바타 — 테두리는 그 멤버의 커서 색 */
function BlockEditorBadge({ editor }) {
  if (!editor) return null;
  return (
    <span
      className="blk-editor"
      title={`최근 수정 · ${editor.name}`}
      style={{ "--vh": hueOf(editor.id) }}
    >
      {editor.profileImg?.startsWith("http") ? (
        <img src={editor.profileImg} alt="" />
      ) : (
        editor.name[0]
      )}
    </span>
  );
}

export { BlockEditBadge, BlockLinkBadge, BlockEditorBadge };
