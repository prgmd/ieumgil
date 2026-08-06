// pages/Dashboard/components/MapPanel.jsx
//
// 카카오 지도가 그려질 빈 상자. 지도 자체는 useKakaoMap 훅이 소유하고,
// 여기서는 컨테이너 div 만 내주고 ref callback 으로 넘긴다 — 그래야 읽기 모드
// 전환으로 div 가 사라졌다 다시 생겨도 훅이 그 순간을 알아채고 다시 바인딩한다.

export function MapPanel({ initMapOnContainer, pinPickMode, onCancelPinPick }) {
  return (
    <div className="panel">
      <h4 className="panel-title">
        지도
        <span
          className="hint-ico"
          tabIndex={0}
          aria-label="지도 사용 안내"
          data-tip="장소를 검색하면 지도가 그 위치로 이동해요. 검색 결과나 지도의 핀을 클릭하면 상세 정보 말풍선이 떠요."
        >
          ⓘ
        </span>
      </h4>
      {/* 배너를 지도 위에 얹어야 해서 위치 기준 상자로 한 번 감싼다 */}
      <div className={`map-wrap${pinPickMode ? " is-picking" : ""}`}>
        {pinPickMode && (
          <div className="map-pick-banner">
            <span>지도를 클릭해 위치를 지정하세요 (Esc 취소)</span>
            <button
              type="button"
              className="map-pick-cancel"
              onClick={onCancelPinPick}
            >
              취소
            </button>
          </div>
        )}
        {/* 높이는 CSS(.map-box)에서 화면 높이에 맞춰 늘린다 — 사이드 폭이
            넓어진 만큼 지도도 남는 공간을 다 쓰게 하기 위함.
            초기화는 ref callback 으로 — getElementById 방식은 로딩 가드가
            null 을 반환하는 동안 컨테이너가 없어 재진입 시 회색 지도가 됐다 */}
        <div
          id="kakao-map-container"
          className="map-box"
          ref={initMapOnContainer}
        />
      </div>
    </div>
  );
}
