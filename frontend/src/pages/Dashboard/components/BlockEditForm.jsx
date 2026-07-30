import React, { useState } from "react";

export function BlockEditForm({ initialData, onSave, onCancel }) {
  const [formData, setFormData] = useState(initialData);

  const handleChange = (e) => {
    const { name, value, type, checked } = e.target;
    setFormData((prev) => ({
      ...prev,
      [name]: type === "checkbox" ? checked : value,
    }));
  };

  return (
    <div
      className="edit-form-container"
      style={{
        padding: "20px",
        backgroundColor: "white",
        borderRadius: "12px",
        boxShadow: "0 4px 12px rgba(0,0,0,0.1)",
      }}
    >
      <h3>블록 수정</h3>

      <div
        style={{
          display: "flex",
          flexDirection: "column",
          gap: "10px",
          marginTop: "15px",
        }}
      >
        <label>
          이름:{" "}
          <input
            name="name"
            value={formData.name || ""}
            onChange={handleChange}
          />
        </label>

        <label>
          카테고리:
          <select
            name="category"
            value={formData.category}
            onChange={handleChange}
          >
            <option value="SPOT">장소 (SPOT)</option>
            <option value="FOOD">식당 (FOOD)</option>
            <option value="STAY">숙소 (STAY)</option>
            <option value="ETC">기타 (ETC)</option>
          </select>
        </label>

        <label>
          소요 시간(분):{" "}
          <input
            type="number"
            step="30"
            name="durationMin"
            value={formData.durationMin || 0}
            onChange={handleChange}
          />
        </label>

        <label>
          <input
            type="checkbox"
            name="isTimeFixed"
            checked={formData.isTimeFixed || false}
            onChange={handleChange}
          />
          일정 시간 고정 (자동 재배치 방지)
        </label>
      </div>

      <div style={{ marginTop: "20px", display: "flex", gap: "10px" }}>
        <button onClick={onCancel}>취소</button>
        <button
          onClick={() => onSave(formData)}
          style={{ backgroundColor: "#4caf50", color: "white" }}
        >
          저장
        </button>
      </div>
    </div>
  );
}
