import { useEffect, useRef, useState } from "react";
import { CAT_TO_SERVER } from "../../../features/dashboard/api/dashboardApi";
import {
  openAddressSearch,
  preloadAddressSearch,
} from "../../../features/dashboard/map/addressLookup";
import { geocodeAddress } from "../../../features/place/api/placeApi";
import { TransitCandidateCard } from "./TransitCandidateCard";
import "./BlockEditForm.css";

// 서버가 lat/lng 를 필수로 보는 장소성 카테고리 — 좌표 없이 보내면 BLOCK400 이다.
// 교통·기타는 지도에 찍을 지점이 없을 수 있어 좌표를 요구하지 않는다.
const PLACE_CATEGORIES = new Set(["SPOT", "FOOD", "STAY"]);

export function BlockEditForm({
  initialData,
  timeString,
  categoryLocked = false,
  lockNotice = "",
  // 소요 시간 상한(분). 보통은 null — 24:00 을 넘기면 넘친 만큼 다음 Day 로
  // 쪼개지므로 제한이 없다. 마지막 Day 의 체인 블록에만 값이 온다(넘길 곳이 없다).
  maxDurationMin = null,
  // 지도에서 찍어 온 위치 { lat, lng, address }. 부모가 지정할 때마다 새 객체를
  // 넘기고, 모달을 닫을 때 null 로 되돌린다.
  pinnedLocation = null,
  onRequestPinPick,
  onSave,
  onCancel,
  onReselectTransport,
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

  // 모달을 연 시점의 값 — 저장 시 "사용자가 실제로 만진 필드"를 가리는 기준이다.
  // 부모가 지금의 블록(items[id])과 비교하면 안 된다: 모달이 열려 있는 사이
  // 다른 멤버가 바꾼 필드까지 "내가 바꾼 것"으로 잡혀, 폼이 들고 있던 옛 값이
  // 함께 전송되고 서버 LWW(수신 시각)가 그걸 최신으로 받아들여 남의 변경을 지운다.
  const baselineRef = useRef(formData);

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

  // 지도에서 찍어 온 위치를 폼에 얹는다 — 좌표·주소만 갈아끼우고 나머지 필드는
  // 손대지 않는다(지정하러 가기 전에 고쳐 둔 이름·비용이 살아 있어야 한다).
  // 부모가 찍을 때마다 새 객체를 주므로 "새로 찍혔다"를 객체 정체성으로 가른다 —
  // 무관한 리렌더로는 같은 객체가 오므로 다시 적용되지 않는다.
  // effect 가 아니라 "렌더 중 보정"인 이유: 좌표 반영은 외부 시스템 동기화가
  // 아니라 prop 변화에 따른 state 조정이다. effect 로 쓰면 lint 가
  // react-hooks/set-state-in-effect 로 막고(값이 한 번 어긋난 채 그려졌다가
  // 다시 그려진다), ref 로 이전 값을 들면 react-hooks/refs 가 막는다.
  const [appliedPin, setAppliedPin] = useState(pinnedLocation);
  if (pinnedLocation && pinnedLocation !== appliedPin) {
    setAppliedPin(pinnedLocation);
    setFormData((prev) => ({
      ...prev,
      lat: pinnedLocation.lat,
      lng: pinnedLocation.lng,
      // 역지오코딩이 빈손이면 주소는 그대로 두고 사용자가 직접 쓰게 한다 —
      // 좌표는 이미 잡혔으니 저장에는 문제가 없다
      ...(pinnedLocation.address ? { address: pinnedLocation.address } : null),
    }));
  }

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
    // 단위 강제는 하지 않는다 — 예전 10분 단위 검증은 KTX 138분·시내버스 23분처럼
    // 교통 후보가 만든 실측값이 걸려, 그 블록의 "모든" 필드 저장을 막는 모순이 있었다
    // (리사이즈는 10분 스냅이지만 그건 입력 편의지 데이터 규칙이 아니다)
    const dur = Number(formData.durationMin);
    if (!Number.isInteger(dur) || dur <= 0) {
      setError("소요 시간은 1분 이상의 정수로 입력해주세요.");
      return;
    }
    // 보통은 24:00 을 넘겨도 된다 — 넘친 만큼은 다음 Day 00:00 에 "(이어서)"
    // 블록으로 쪼개진다. 상한이 넘어온다면 마지막 Day 라 넘길 곳이 없다는 뜻이다.
    if (maxDurationMin != null && dur > maxDurationMin) {
      setError(
        `마지막 날이라 24:00을 넘길 수 없어요. (이 블록은 최대 ${maxDurationMin}분)`,
      );
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
          setError(
            "명소·식당·숙소 블록은 위치가 필요해요. 주소를 검색하거나 지도에서 위치를 지정해주세요.",
          );
          return;
        }
        try {
          const coords = await geocodeAddress(formData.address);
          lat = coords.lat;
          lng = coords.lng;
        } catch {
          setError(
            "입력한 주소의 좌표를 찾지 못했어요. 주소 검색으로 고르거나 지도에서 위치를 지정해주세요.",
          );
          return;
        }
      }

      // 두 번째 인자 = 모달 오픈 시점 스냅샷. 부모는 이것과 비교해 사용자가
      // 만진 필드만 골라 PATCH 한다(원격 변경 되덮기 방지).
      await onSave({ ...formData, lat, lng }, baselineRef.current);
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

      {formData.category === "TRANSPORT" && (
        <div className="bef-row">
          <div className="bef-col">
            <label className="bef-label">이동 수단</label>
            {initialData.transportMeta?.chosen ? (
              <TransitCandidateCard
                candidate={initialData.transportMeta.chosen}
                mode="view"
                selectedDepartureName={
                  initialData.transportMeta.chosen.departureName ?? null
                }
              />
            ) : (
              <p className="bef-addr-hint">
                이 블록은 예전 방식으로 생성됐어요. 다시 계산하려면 변경을
                눌러주세요.
              </p>
            )}
            <button
              type="button"
              className="bef-addr-btn"
              onClick={() => onReselectTransport?.(initialData)}
            >
              변경
            </button>
          </div>
        </div>
      )}

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
            {/* 검색으로 안 나오는 곳(신규 가게·이름 없는 지점)을 위한 탈출구.
                주소 검색과 같은 조건으로 잠근다 — 기존 블록은 서버가 주소·좌표
                갱신을 받지 않아(BLOCK400_2) 찍어 봐야 저장되지 않는다 */}
            <button
              type="button"
              className="bef-addr-btn"
              onClick={onRequestPinPick}
              disabled={categoryLocked || addrBusy}
            >
              지도에서 위치 지정
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
                  명소·식당·숙소는 좌표가 필요해요 — 주소를 검색하거나 지도에서
                  위치를 지정해주세요.
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
          <label className="bef-label">
            소요 (분 · 10분 단위)
            {maxDurationMin != null && ` · 마지막 날이라 최대 ${maxDurationMin}분`}
          </label>
          <input
            className="bef-input"
            type="number"
            step="10"
            min="10"
            max={maxDurationMin ?? undefined}
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
