import { useState } from 'react';
import Modal from '../../My/shared/ui/Modal';
import TransportPicker from '../../My/shared/ui/TransportPicker';
import { useToastStore } from '../../../global/stores/toastStore';
import { searchPlaces } from '../../../features/place/api/placeApi';
import { createBlock } from '../../../features/dashboard/api/dashboardApi';

function todayISO() {
  return new Date().toISOString().slice(0, 10);
}

const initialForm = {
  name: '',
  destination: '',
  budgetHeadcount: 4,
  startDate: todayISO(),
  endDate: todayISO(),
  transportPrefs: [], // 기본값 없음 — 사용자가 직접 골라야 함
  targetBudget: '',
};

/**
 * @param onCreate (form) => Promise<project> — 목록을 소유한 페이지가 내려준다.
 *        groupId 는 훅에 이미 묶여 있으므로 여기서 알 필요가 없다.
 */
export default function CreateProjectModal({ open, onClose, onCreate }) {
  const showToast = useToastStore((s) => s.show);

  const [form, setForm] = useState(initialForm);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // ── 출발지점 (선택) — 카카오 장소 검색으로 고른다 ──
  // 고르면 프로젝트 생성 직후 그 좌표로 Day 1 09:00 에 "시작 지점" 블록을 만든다.
  // 좌표를 아는 시점이 여기뿐이라 블록 생성도 여기서 한다 — 대시보드 입장 시
  // 지오코딩을 다시 하는 방식은 실패·동시 입장 중복의 여지가 있었다.
  const [depQuery, setDepQuery] = useState('');
  const [depResults, setDepResults] = useState(null); // null = 검색 전
  const [depSearching, setDepSearching] = useState(false);
  const [departure, setDeparture] = useState(null); // 고른 장소 {name, address, lat, lng, placeId}

  async function handleDepartureSearch() {
    const keyword = depQuery.trim();
    if (!keyword || depSearching) return;
    setDepSearching(true);
    try {
      // 서버는 15건까지 준다 — 좁은 드롭다운이라 기존대로 5건만 보여준다.
      setDepResults((await searchPlaces(keyword)).slice(0, 5));
    } catch (e) {
      setError(e?.message ?? '장소를 검색하지 못했어요.');
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
    setForm(initialForm);
    setError('');
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
    if (!form.name.trim()) {
      setError('프로젝트 이름을 입력해주세요.');
      return;
    }
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
      const project = await onCreate(form);

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

      <label>프로젝트 이름 *</label>
      <input
        placeholder="예: 가을 전주 미식 여행"
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
          <input
            type="number"
            min={1}
            value={form.budgetHeadcount}
            onChange={(e) => update('budgetHeadcount', e.target.value)}
          />
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
        <>
          <div className="dep-search">
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
            <button
              type="button"
              className="btn btn-gh dep-search-btn"
              onClick={handleDepartureSearch}
              disabled={depSearching || !depQuery.trim()}
            >
              {depSearching ? '검색 중…' : '장소 검색'}
            </button>
          </div>
          {depResults !== null && (
            <div className="dep-results">
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
        </>
      )}

      <div className="r2">
        <div>
          <label>여행 시작일 *</label>
          <input
            type="date"
            value={form.startDate}
            onChange={(e) => update('startDate', e.target.value)}
          />
        </div>
        <div>
          <label>여행 종료일 *</label>
          <input
            type="date"
            value={form.endDate}
            min={form.startDate}
            onChange={(e) => update('endDate', e.target.value)}
          />
        </div>
      </div>

      <TransportPicker
        value={form.transportPrefs}
        onChange={(next) => update('transportPrefs', next)}
      />

      <label>목표 예산 (총액, 원)</label>
      <input
        type="number"
        min={0}
        step={10000}
        placeholder="예: 600000"
        value={form.targetBudget}
        onChange={(e) => update('targetBudget', e.target.value)}
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
