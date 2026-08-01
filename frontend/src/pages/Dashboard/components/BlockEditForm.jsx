import { useState } from "react";
import { CAT_TO_SERVER } from "../../../features/dashboard/api/dashboardApi";
import "./BlockEditForm.css";


export function BlockEditForm({
  initialData,
  timeString,
  categoryLocked = false,
  onSave,
  onCancel,
}) {
  const [formData, setFormData] = useState({
    id: initialData.id,
    // cat(소문자)↔category(대문자)는 단순 대소문자 변환이 아니다(trans↔TRANSPORT).
    // 어댑터의 매핑을 재사용한다.
    category: CAT_TO_SERVER[initialData.cat] ?? "ETC",
    subCategory: initialData.sub || "",
    name: initialData.name || "",
    address: initialData.address || "",
    durationMin: initialData.dur || 60,
    budget: initialData.cost || 0,
    detail: initialData.detail || "",
  });

  // 저장이 서버 왕복이 되면서(2단계) 중복 제출을 막는다.
  // 실패 안내·모달 유지는 부모(onSave)가 처리하므로 여기서는 상태만 되돌린다.
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const handleSave = async () => {
    // 서버까지 가면 BLOCK400 계열로 거절될 값을 여기서 먼저 막는다
    if (!formData.name.trim()) {
      setError("블록 이름을 입력해주세요.");
      return;
    }
    const dur = Number(formData.durationMin);
    if (!Number.isInteger(dur) || dur <= 0 || dur % 30 !== 0) {
      setError("소요 시간은 30분 단위로 입력해주세요. (예: 30, 60, 90)");
      return;
    }

    setError("");
    setSaving(true);
    try {
      await onSave(formData);
    } catch {
      /* 부모가 토스트로 안내한다 — 모달은 열린 채 재시도 가능 */
    } finally {
      setSaving(false);
    }
  };

  const handleChange = (e) => {
    const { name, value } = e.target;
    setFormData((prev) => ({ ...prev, [name]: value }));
  };

  return (
    <div className="bef">
      <div className="bef-head">
        <h2 className="bef-title">블록 상세</h2>
        <p className="bef-sub">
          장소명·주소·시간은 자동으로 채워진 뒤 수정할 수 있어요. 비용은 직접
          입력합니다.
        </p>
      </div>

      <div className="bef-row">
        <div className="bef-col">
          <label className="bef-label">대분류</label>
          <select
            className="bef-input"
            name="category"
            value={formData.category}
            onChange={handleChange}
            disabled={categoryLocked}
          >
            <option value="SPOT">명소/활동</option>
            <option value="FOOD">식당</option>
            <option value="STAY">숙소</option>
            <option value="TRANSPORT">교통</option>
            <option value="ETC">기타</option>
          </select>
          {categoryLocked && (
            <p style={{ fontSize: "11px", color: "#8c7b70", margin: "4px 0 0" }}>
              카테고리·소분류·주소는 만들 때만 정할 수 있어요
            </p>
          )}
        </div>

        <div className="bef-col">
          <label className="bef-label">소분류</label>

           {/* 소분류·주소도 카테고리처럼 생성 시에만 — 서버 LWW 화이트리스트에 없어
              (BLOCK400_2) 기존 블록 수정으로는 저장할 수 없다 */}
               
          <input
            className="bef-input"
            name="subCategory"
            value={formData.subCategory}
            onChange={handleChange}
            placeholder="예: 회, 산책"
            disabled={categoryLocked}
          />
        </div>
      </div>

      <div className="bef-row">
        <div className="bef-col">
          <label className="bef-label">장소명 (수정 가능)</label>
          <input
            className="bef-input"
            name="name"
            value={formData.name}
            onChange={handleChange}
          />
        </div>
      </div>

      <div className="bef-row">
        <div className="bef-col">
          <label className="bef-label">주소 (자동 입력 · 수정 가능)</label>

          <input
            className="bef-input"
            name="address"
            value={formData.address}
            onChange={handleChange}
            disabled={categoryLocked}
          />
        </div>
      </div>

      <div className="bef-row">
        <div className="bef-col">
          <label className="bef-label">시간 (체인에서 자동 계산)</label>
          <input
            className="bef-input"
            value={timeString || "시간 정보 없음"}
            readOnly
          />
        </div>
        <div className="bef-col">
          <label className="bef-label">소요 (분 · 30분 단위)</label>
          <input
            className="bef-input"
            type="number"
            step="30"
            name="durationMin"
            value={formData.durationMin}
            onChange={handleChange}
          />
        </div>
      </div>

      <div className="bef-row">
        <div className="bef-col">
          <label className="bef-label">비용 (직접 입력 · 원)</label>
          <input
            className="bef-input"
            type="number"
            name="budget"
            value={formData.budget}
            onChange={handleChange}
          />
        </div>
      </div>

      <div className="bef-row">
        <div className="bef-col">
          <label className="bef-label">비고 — 메모·세부사항</label>
          <textarea
            className="bef-textarea"
            name="detail"
            value={formData.detail}
            onChange={handleChange}
            maxLength={500}
            placeholder="상세 내용을 입력하세요"
          />
        </div>
      </div>

      {error && (
        <p style={{ color: "#9c3b3b", fontSize: "12.5px", margin: "0 0 10px" }}>
          {error}
        </p>
      )}

      <div className="bef-foot">
        <div className="bef-editor">✎ 마지막 수정 · 민준</div>
        <div className="bef-btns">
          <button className="bef-btn bef-btn-cancel" onClick={onCancel}>
            닫기
          </button>
          {/* handleSave 를 거쳐야 입력 검증·중복 제출 방지(saving)가 동작한다 */}
          <button
            className="bef-btn bef-btn-save"
            onClick={handleSave}
            disabled={saving}
          >
            {saving ? "저장 중..." : "저장"}
          </button>
        </div>
      </div>
    </div>
  );
}
