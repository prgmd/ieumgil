import { useState } from "react";

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

  const styles = {
    container: {
      width: "460px",
      backgroundColor: "#fbf8f1",
      borderRadius: "16px",
      padding: "28px",
      boxShadow: "0 10px 30px rgba(0,0,0,0.15)",
      fontFamily: "sans-serif",
      color: "#4a3a31",
      maxHeight: "90vh",
      overflowY: "auto",
    },
    header: { marginBottom: "24px" },
    title: {
      fontSize: "22px",
      fontWeight: "800",
      marginBottom: "6px",
      color: "#3d2b22",
    },
    subtitle: { fontSize: "13px", color: "#8c7b70" },
    row: { display: "flex", gap: "16px", marginBottom: "16px" },
    col: { flex: 1, display: "flex", flexDirection: "column" },
    label: {
      fontSize: "13px",
      fontWeight: "700",
      color: "#7a6a5c",
      marginBottom: "8px",
    },
    input: {
      padding: "12px 14px",
      borderRadius: "10px",
      border: "1px solid #e6dec8",
      backgroundColor: "#fff",
      fontSize: "15px",
      color: "#333",
      outline: "none",
      transition: "border 0.2s",
    },
    readOnlyInput: {
      padding: "12px 14px",
      borderRadius: "10px",
      border: "1px solid #e6dec8",
      backgroundColor: "#f0ebd8",
      fontSize: "15px",
      color: "#555",
      outline: "none",
    },
    textarea: {
      padding: "12px 14px",
      borderRadius: "10px",
      border: "1px solid #e6dec8",
      backgroundColor: "#fff",
      fontSize: "15px",
      color: "#333",
      outline: "none",
      minHeight: "80px",
      resize: "none",
    },
    footer: {
      display: "flex",
      justifyContent: "space-between",
      alignItems: "center",
      marginTop: "30px",
    },
    editorInfo: { fontSize: "12px", color: "#8c7b70" },
    btnGroup: { display: "flex", gap: "8px" },
    btnCancel: {
      padding: "10px 24px",
      borderRadius: "10px",
      border: "1px solid #d9cebc",
      backgroundColor: "#fff",
      color: "#4a3a31",
      fontWeight: "bold",
      fontSize: "15px",
      cursor: "pointer",
    },
    btnSave: {
      padding: "10px 24px",
      borderRadius: "10px",
      border: "none",
      backgroundColor: "#7c5443",
      color: "#fff",
      fontWeight: "bold",
      fontSize: "15px",
      cursor: "pointer",
    },
  };

  return (
    <div style={styles.container}>
      <div style={styles.header}>
        <h2 style={styles.title}>블록 상세</h2>
        <p style={styles.subtitle}>
          장소명·주소·시간은 자동으로 채워진 뒤 수정할 수 있어요. 비용은 직접
          입력합니다.
        </p>
      </div>

      <div style={styles.row}>
        <div style={styles.col}>
          <label style={styles.label}>대분류</label>
          <select
            style={styles.input}
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
        <div style={styles.col}>
          <label style={styles.label}>소분류</label>
          <input
            style={styles.input}
            name="subCategory"
            value={formData.subCategory}
            onChange={handleChange}
            placeholder="예: 회, 산책"
          />
        </div>
      </div>

      <div style={styles.row}>
        <div style={styles.col}>
          <label style={styles.label}>장소명 (수정 가능)</label>
          <input
            style={styles.input}
            name="name"
            value={formData.name}
            onChange={handleChange}
          />
        </div>
      </div>

      <div style={styles.row}>
        <div style={styles.col}>
          <label style={styles.label}>주소 (자동 입력 · 수정 가능)</label>
          <input
            style={styles.input}
            name="address"
            value={formData.address}
            onChange={handleChange}
          />
        </div>
      </div>

      <div style={styles.row}>
        <div style={styles.col}>
          <label style={styles.label}>시간 (체인에서 자동 계산)</label>
          <input
            style={styles.readOnlyInput}
            value={timeString || "시간 정보 없음"}
            readOnly
          />
        </div>
        <div style={styles.col}>
          <label style={styles.label}>소요 (분 · 30분 단위)</label>
          <input
            style={styles.input}
            type="number"
            step="30"
            name="durationMin"
            value={formData.durationMin}
            onChange={handleChange}
          />
        </div>
      </div>

      <div style={styles.row}>
        <div style={styles.col}>
          <label style={styles.label}>비용 (직접 입력 · 원)</label>
          <input
            style={styles.input}
            type="number"
            name="budget"
            value={formData.budget}
            onChange={handleChange}
          />
        </div>
      </div>

      <div style={styles.row}>
        <div style={styles.col}>
          <label style={styles.label}>비고 — 메모·세부사항</label>
          <textarea
            style={styles.textarea}
            name="memo"
            value={formData.memo}
            onChange={handleChange}
            placeholder="상세 내용을 입력하세요"
          />
        </div>
      </div>

      <div style={styles.footer}>
        <div style={styles.editorInfo}>✏️ 마지막 수정 · 민준</div>
        <div style={styles.btnGroup}>
          <button style={styles.btnCancel} onClick={onCancel}>
            닫기
          </button>
          <button style={styles.btnSave} onClick={() => onSave(formData)}>
            저장
          </button>
        </div>
      </div>
    </div>
  );
}
