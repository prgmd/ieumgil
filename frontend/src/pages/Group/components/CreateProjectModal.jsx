import { useState, useRef, useEffect } from 'react';
import Modal from '../../My/shared/ui/Modal';
import TransportPicker from '../../My/shared/ui/TransportPicker';
import { DatePicker } from '../../../global/components/DatePicker';
import { MoneyInput } from '../../../global/components/MoneyInput';
import { useToastStore } from '../../../global/stores/toastStore';
import { searchPlaces } from '../../../features/place/api/placeApi';
import { createBlock } from '../../../features/dashboard/api/dashboardApi';

// 로컬(KST) 기준 오늘 — toISOString()은 UTC라 오전 시간대엔 어제로 잡혔다.
function todayISO() {
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

// 'YYYY-MM-DD' → 'YYMMDD' (예: '2026-08-04' → '260804')
function yymmdd(iso) {
  return iso ? iso.slice(2).replace(/-/g, '') : '';
}

// 모달을 열 때마다 새로 만든다 — 날짜(오늘)와 기본 인원(그룹 인원)이 그 시점 값이어야
// 한다. 모듈 상수로 한 번만 평가하면 앱을 켜둔 채 날짜가 바뀌어도 옛날 값이 남는다.
function makeInitialForm(headcount) {
  return {
    name: '',
    destination: '',
    budgetHeadcount: headcount,
    startDate: todayISO(),
    endDate: todayISO(),
    transportPrefs: [], // 기본값 없음 — 사용자가 직접 골라야 함
    targetBudget: '',
  };
}

/**
 * @param onCreate (form) => Promise<project> — 목록을 소유한 페이지가 내려준다.
 *        groupId 는 훅에 이미 묶여 있으므로 여기서 알 필요가 없다.
 */
export default function CreateProjectModal({
  open,
  onClose,
  onCreate,
  defaultHeadcount = 1,
}) {
  const showToast = useToastStore((s) => s.show);

  const [form, setForm] = useState(() => makeInitialForm(defaultHeadcount));
  const [error, setError] = useState('');
  const [depError, setDepError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // ── 출발지점 (선택) — 카카오 장소 검색으로 고른다 ──
  // 고르면 프로젝트 생성 직후 그 좌표로 Day 1 09:00 에 "시작 지점" 블록을 만든다.
  // 좌표를 아는 시점이 여기뿐이라 블록 생성도 여기서 한다 — 대시보드 입장 시
  // 지오코딩을 다시 하는 방식은 실패·동시 입장 중복의 여지가 있었다.
  const [depQuery, setDepQuery] = useState('');
  const [depResults, setDepResults] = useState(null); // null = 검색 전
  const [depSearching, setDepSearching] = useState(false);
  const [departure, setDeparture] = useState(null); // 고른 장소 {name, address, lat, lng, placeId}
  const depWrapRef = useRef(null);

  // 검색 결과가 떠 있을 때 바깥을 클릭하면 닫는다(autocomplete 관례).
  useEffect(() => {
    if (depResults === null) return undefined;
    const onDown = (e) => {
      if (depWrapRef.current && !depWrapRef.current.contains(e.target)) {
        setDepResults(null);
      }
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [depResults]);

  // 열 때마다 오늘 날짜·그룹 인원으로 폼을 새로 잡는다(모달은 상시 마운트라 마운트
  // 시점 값이 굳는 것을 막는다). "렌더 중 state 보정" 패턴.
  const [wasOpen, setWasOpen] = useState(open);
  if (open !== wasOpen) {
    setWasOpen(open);
    if (open) {
      setForm(makeInitialForm(defaultHeadcount));
      setError('');
      setDepError('');
      setDeparture(null);
    }
  }

  async function handleDepartureSearch() {
    const keyword = depQuery.trim();
    if (!keyword || depSearching) return;
    setDepSearching(true);
    try {
      // 서버는 15건까지 준다 — 좁은 드롭다운이라 기존대로 5건만 보여준다.
      setDepError('');
      setDepResults((await searchPlaces(keyword)).slice(0, 5));
    } catch (e) {
      setDepError(e?.message ?? '장소를 검색하지 못했어요.');
    } finally {
      setDepSearching(false);
    }
  }

  function pickDeparture(place) {
    setDeparture(place);
    setDepResults(null);
    setDepQuery('');
  }

  function update(field, value) {
    setForm((f) => ({ ...f, [field]: value }));
  }

  function reset() {
    setForm(makeInitialForm(defaultHeadcount));
    setError('');
    setDepError('');
    setSubmitting(false);
    setDepQuery('');
    setDepResults(null);
    setDepSearching(false);
    setDeparture(null);
  }

  function handleClose() {
    reset();
    onClose();
  }

  async function handleSubmit() {
    // 이름은 선택 — 비우면 날짜로 자동 생성한다(예: '260804~260809 여행').
    // 어차피 생성 후 프로젝트 수정에서 바꿀 수 있어 초기 입력 부담을 던다.
    if (!form.destination.trim()) {
      setError('목적지를 입력해주세요.');
      return;
    }
    if (!form.budgetHeadcount || Number(form.budgetHeadcount) < 1) {
      setError('여행 인원은 1명 이상이어야 해요.');
      return;
    }
    // 서버가 필수로 받는다(ProjectReqDTO.Create @NotNull). 빈 값으로 보내면
    // enum 변환이 실패해 COMMON400_4 로 떨어지므로 여기서 먼저 막는다.
    if (form.transportPrefs.length < 1) {
      setError('주요 이동수단을 선택해주세요.');
      return;
    }
    if (!form.startDate || !form.endDate) {
      setError('여행 시작일과 종료일을 선택해주세요.');
      return;
    }
    if (form.startDate > form.endDate) {
      setError('종료일은 시작일보다 빠를 수 없어요.');
      return;
    }

    setError('');
    setSubmitting(true);
    try {
      const name =
        form.name.trim() ||
        `${yymmdd(form.startDate)}~${yymmdd(form.endDate)} 여행`;
      const project = await onCreate({ ...form, name });

      // 출발지점을 골랐으면 Day 1 09:00 에 시작 블록을 함께 만든다(선택 사항).
      // 프로젝트는 이미 생겼으므로 이 단계가 실패해도 생성 자체는 성공으로 두고,
      // 대시보드에서 직접 추가하라고만 알린다.
      if (departure && project?.projectId) {
        try {
          await createBlock(project.projectId, {
            cat: 'spot',
            sub: '시작 지점',
            name: departure.name,
            address: departure.address,
            dur: 60,
            startMins: 540, // Day 1 09:00
            cost: 0,
            lat: departure.lat,
            lng: departure.lng,
            placeId: departure.placeId,
            source: 'MANUAL',
          });
        } catch {
          showToast('출발지점 블록은 만들지 못했어요 — 대시보드에서 직접 추가해주세요.');
        }
      }

      showToast('새 프로젝트가 생성됐어요 ✈');
      handleClose();
    } catch {
      setError('프로젝트를 만들지 못했어요. 잠시 후 다시 시도해주세요.');
      setSubmitting(false);
    }
  }

  return (
    <Modal open={open} onClose={handleClose}>
      <h3>새 여행 프로젝트</h3>
      <p className="s">기본 정보를 입력하면 챗봇 이음이가 이를 참고해 후보 블록을 준비해요.</p>

      <label>프로젝트 이름</label>
      <input
        placeholder="비우면 날짜로 자동 생성돼요 (예: 260804~260809 여행)"
        maxLength={30}
        value={form.name}
        onChange={(e) => update('name', e.target.value)}
        autoFocus
      />

      <div className="r2">
        <div>
          <label>목적지 *</label>
          <input
            placeholder="예: 부산"
            value={form.destination}
            onChange={(e) => update('destination', e.target.value)}
          />
        </div>
        <div>
          <label>여행 인원 *</label>
          <div className="stepper">
            <button
              type="button"
              onClick={() =>
                update(
                  'budgetHeadcount',
                  Math.max(1, Number(form.budgetHeadcount) - 1),
                )
              }
              aria-label="인원 줄이기"
            >
              −
            </button>
            <span className="stepper-val">{form.budgetHeadcount}명</span>
            <button
              type="button"
              onClick={() =>
                update('budgetHeadcount', Number(form.budgetHeadcount) + 1)
              }
              aria-label="인원 늘리기"
            >
              +
            </button>
          </div>
        </div>
      </div>

      <label>출발지점 (선택)</label>
      {departure ? (
        <div className="dep-chip">
          <span className="dep-chip-main">
            📍 <b>{departure.name}</b>
            {departure.address && (
              <span className="dep-chip-addr">{departure.address}</span>
            )}
          </span>
          <button
            type="button"
            className="dep-chip-x"
            onClick={() => setDeparture(null)}
            aria-label="출발지점 선택 해제"
          >
            ✕
          </button>
        </div>
      ) : (
        <div className="dep-search-wrap">
          <div className="dep-search">
            {/* 결과 드롭다운을 인풋 폭에 맞추려고 인풋만 감싼다(버튼 제외) */}
            <div className="dep-input-wrap" ref={depWrapRef}>
              <input
                placeholder="예: 전주역, 김포공항 — 고르면 Day 1에 시작 블록이 놓여요"
                value={depQuery}
                onChange={(e) => setDepQuery(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.nativeEvent.isComposing) {
                    e.preventDefault(); // 폼 submit 으로 새지 않게
                    handleDepartureSearch();
                  }
                }}
              />
              {depResults !== null && (
                <div className="dep-results cream-scroll-round">
                  {depResults.map((p) => (
                    <button
                      key={p.placeId}
                      type="button"
                      className="dep-result"
                      onClick={() => pickDeparture(p)}
                    >
                      <b>{p.name}</b>
                      <span>{p.address}</span>
                    </button>
                  ))}
                  {depResults.length === 0 && (
                    <p className="dep-empty">검색 결과가 없어요 — 다른 키워드로 시도해보세요.</p>
                  )}
                </div>
              )}
            </div>
            <button
              type="button"
              className="btn btn-gh dep-search-btn"
              onClick={handleDepartureSearch}
              disabled={depSearching || !depQuery.trim()}
            >
              {depSearching ? '검색 중…' : '장소 검색'}
            </button>
          </div>
          {depError && <p className="dep-empty">{depError}</p>}
        </div>
      )}

      <div className="r2">
        <div>
          <label>여행 시작일 *</label>
          <DatePicker
            value={form.startDate}
            onChange={(v) =>
              // 시작일이 종료일보다 뒤로 가면 종료일도 같이 끌어올린다
              setForm((f) => ({
                ...f,
                startDate: v,
                endDate: f.endDate && f.endDate < v ? v : f.endDate,
              }))
            }
          />
        </div>
        <div>
          <label>여행 종료일 *</label>
          <DatePicker
            value={form.endDate}
            min={form.startDate}
            onChange={(v) => update('endDate', v)}
          />
        </div>
      </div>

      <TransportPicker
        value={form.transportPrefs}
        onChange={(next) => update('transportPrefs', next)}
      />

      <label>목표 예산 (총액, 원)</label>
      <MoneyInput
        placeholder="예: 600000"
        value={form.targetBudget}
        onChange={(v) => update('targetBudget', v)}
      />

      {error && <div className="code-err">{error}</div>}

      <div className="foot">
        <button className="btn btn-gh" onClick={handleClose}>
          취소
        </button>
        <button className="btn btn-acc" onClick={handleSubmit} disabled={submitting}>
          {submitting ? '만드는 중…' : '만들기'}
        </button>
      </div>
    </Modal>
  );
}
