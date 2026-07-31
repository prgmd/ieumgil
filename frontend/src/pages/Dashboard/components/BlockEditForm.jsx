import { useState } from "react";
import { CAT_TO_SERVER } from "../../../features/dashboard/api/dashboardApi";

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
        <div style={styles.col}>
          <label style={styles.label}>소분류</label>
          {/* 소분류·주소도 카테고리처럼 생성 시에만 — 서버 LWW 화이트리스트에 없어
              (BLOCK400_2) 기존 블록 수정으로는 저장할 수 없다 */}
          <input
            style={styles.input}
            name="subCategory"
            value={formData.subCategory}
            onChange={handleChange}
            placeholder="예: 회, 산책"
            disabled={categoryLocked}
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
          <label style={styles.label}>
            주소 {categoryLocked ? "(생성 시 확정)" : "(자동 입력 · 수정 가능)"}
          </label>
          <input
            style={styles.input}
            name="address"
            value={formData.address}
            onChange={handleChange}
            disabled={categoryLocked}
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
          <label style={styles.label}>비고 — 메모·세부사항 (최대 500자)</label>
          <textarea
            style={styles.textarea}
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

      <div style={styles.footer}>
        <div style={styles.editorInfo}>✏️ 마지막 수정 · 민준</div>
        <div style={styles.btnGroup}>
          <button style={styles.btnCancel} onClick={onCancel} disabled={saving}>
            닫기
          </button>
          <button style={styles.btnSave} onClick={handleSave} disabled={saving}>
            {saving ? "저장 중…" : "저장"}
          </button>
        </div>
      </div>
    </div>
  );
}
