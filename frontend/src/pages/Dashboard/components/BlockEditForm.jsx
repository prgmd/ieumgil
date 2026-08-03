import { useEffect, useState } from "react";
import { CAT_TO_SERVER } from "../../../features/dashboard/api/dashboardApi";
import {
  openAddressSearch,
  geocodeAddress,
  preloadAddressSearch,
} from "../../../features/dashboard/map/addressLookup";
import "./BlockEditForm.css";

// 서버가 lat/lng 를 필수로 보는 장소성 카테고리 — 좌표 없이 보내면 BLOCK400 이다.
// 교통·기타는 지도에 찍을 지점이 없을 수 있어 좌표를 요구하지 않는다.
const PLACE_CATEGORIES = new Set(["SPOT", "FOOD", "STAY"]);

export function BlockEditForm({
  initialData,
  timeString,
  categoryLocked = false,
  lockNotice = "",
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
    // 도로명 주소만 담는다 — 동·호수 같은 상세는 아래 "비고" 메모에 적는다.
    // 지오코딩은 이 값 그대로 태우므로 좌표를 못 찾을 군더더기가 섞이면 안 된다.
    address: initialData.address || "",
    // 좌표는 주소와 한 몸이다 — 주소가 바뀌면 같이 갱신되거나 비워져야 한다
    lat: initialData.lat ?? null,
    lng: initialData.lng ?? null,
    durationMin: initialData.dur || 60,
    budget: initialData.cost || 0,
    detail: initialData.detail || "",
  });

  // 저장이 서버 왕복이 되면서(2단계) 중복 제출을 막는다.
  // 실패 안내·모달 유지는 부모(onSave)가 처리하므로 여기서는 상태만 되돌린다.
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [addrBusy, setAddrBusy] = useState(false);

  // categoryLocked 는 "서버에 이미 있는 블록"과 같은 뜻이다(부모가 !isTempId 로 넘긴다).
  // 기존 블록은 주소·좌표를 아예 보내지 않으므로 좌표 검사도 새 블록에서만 한다.
  const isNew = !categoryLocked;
  const needsCoords = isNew && PLACE_CATEGORIES.has(formData.category);
  const hasCoords = formData.lat != null && formData.lng != null;

  // 폼이 열리는 동안 미리 받아 둔다 — 버튼을 누른 뒤에 받으면 그 사이에
  // 팝업 허용 자격이 만료돼 차단될 수 있다.
  useEffect(() => {
    preloadAddressSearch();
  }, []);

  /** 도로명 주소 팝업 → 고른 주소를 좌표까지 변환해 폼에 채운다 */
  const handleAddressSearch = async () => {
    setError("");
    setAddrBusy(true);
    try {
      const picked = await openAddressSearch();
      if (!picked) return; // 고르지 않고 닫음

      const address = picked.roadAddress || picked.jibunAddress;
      const coords = await geocodeAddress(address);
      setFormData((prev) => ({
        ...prev,
        address,
        lat: coords.lat,
        lng: coords.lng,
      }));
    } catch (e) {
      setError(e?.message ?? "주소를 불러오지 못했어요.");
    } finally {
      setAddrBusy(false);
    }
  };

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
      let { lat, lng } = formData;

      // 주소를 손으로 고쳐 넣었으면 좌표가 비어 있다 — 저장 직전에 한 번 더 변환해
      // 본다. 여기서 막아 주지 않으면 서버가 BLOCK400 으로 거절하는데, 그 메시지만
      // 봐서는 "주소를 검색으로 고르라"는 걸 알 수 없다.
      if (needsCoords && !hasCoords) {
        if (!formData.address.trim()) {
          setError("명소·식당·숙소 블록은 주소가 필요해요. 주소 검색으로 골라주세요.");
          return;
        }
        try {
          const coords = await geocodeAddress(formData.address);
          lat = coords.lat;
          lng = coords.lng;
        } catch {
          setError(
            "입력한 주소의 좌표를 찾지 못했어요. 주소 검색으로 골라주세요.",
          );
          return;
        }
      }

      await onSave({ ...formData, lat, lng });
    } catch {
      /* 부모가 토스트로 안내한다 — 모달은 열린 채 재시도 가능 */
    } finally {
      setSaving(false);
    }
  };

  const handleChange = (e) => {
    const { name, value } = e.target;
    setFormData((prev) => ({
      ...prev,
      [name]: value,
      // 주소를 직접 고치면 기존 좌표는 더 이상 그 주소의 것이 아니다 — 남겨 두면
      // 엉뚱한 자리에 핀이 찍힌다. 저장 시점에 다시 지오코딩한다.
      ...(name === "address" ? { lat: null, lng: null } : null),
    }));
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
          <label className="bef-label">
            주소{needsCoords ? " (필수)" : ""} — 검색해서 고르면 좌표까지 채워져요
          </label>

          <div className="bef-addr">
            <input
              className="bef-input"
              name="address"
              value={formData.address}
              onChange={handleChange}
              disabled={categoryLocked}
              placeholder="주소 검색을 눌러 도로명 주소를 고르세요"
            />
            <button
              type="button"
              className="bef-addr-btn"
              onClick={handleAddressSearch}
              disabled={categoryLocked || addrBusy}
            >
              {addrBusy ? "불러오는 중..." : "주소 검색"}
            </button>
          </div>

          {/* 좌표가 잡혔는지를 눈에 보이게 한다 — 저장 버튼을 눌러서야
              "좌표가 없다"는 걸 알게 되면 늦다 */}
          {!categoryLocked &&
            (hasCoords ? (
              <p className="bef-addr-hint is-ok">
                ✓ 좌표 확인됨 ({formData.lat.toFixed(5)},{" "}
                {formData.lng.toFixed(5)})
              </p>
            ) : (
              needsCoords && (
                <p className="bef-addr-hint is-warn">
                  명소·식당·숙소는 좌표가 필요해요 — 주소 검색으로 골라주세요.
                </p>
              )
            ))}
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
        {/* 다른 멤버가 같은 블록을 편집 중일 때만 채워진다(advisory 락, 6단계).
            빈 div 를 유지해 space-between 레이아웃에서 버튼이 왼쪽으로 쏠리지 않게 한다. */}
        <div className="bef-editor">{lockNotice}</div>
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
