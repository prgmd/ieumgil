import { useState } from "react";
import "./BlockEditForm.css";

export function BlockEditForm({ initialData, timeString, onSave, onCancel }) {
  const [formData, setFormData] = useState({
    id: initialData.id,
    category: initialData.cat ? initialData.cat.toUpperCase() : "SPOT",
    subCategory: initialData.sub || "",
    name: initialData.name || "",
    address: initialData.addr || "",
    durationMin: initialData.dur || 60,
    budget: initialData.cost || 0,
    memo: initialData.memo || "",
  });

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
          >
            <option value="SPOT">명소/활동</option>
            <option value="FOOD">식당</option>
            <option value="STAY">숙소</option>
            <option value="TRANSPORT">교통</option>
            <option value="ETC">기타</option>
          </select>
        </div>
        <div className="bef-col">
          <label className="bef-label">소분류</label>
          <input
            className="bef-input"
            name="subCategory"
            value={formData.subCategory}
            onChange={handleChange}
            placeholder="예: 회, 산책"
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
            name="memo"
            value={formData.memo}
            onChange={handleChange}
            placeholder="상세 내용을 입력하세요"
          />
        </div>
      </div>

      <div className="bef-foot">
        <div className="bef-editor">✎ 마지막 수정 · 민준</div>
        <div className="bef-btns">
          <button className="bef-btn bef-btn-cancel" onClick={onCancel}>
            닫기
          </button>
          <button
            className="bef-btn bef-btn-save"
            onClick={() => onSave(formData)}
          >
            저장
          </button>
        </div>
      </div>
    </div>
  );
}
