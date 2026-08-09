// pages/Dashboard/components/SearchPanel.jsx
//
// 카카오 장소 검색 패널 — 검색창과 결과 목록. 결과 카드는 눌러서 지도를 옮기고,
// 끌어다 후보 목록에 담는다(드래그는 SearchResultDraggable 이 맡는다).
// 검색 상태·지도 조작은 전부 useKakaoMap 훅이 들고 있고 여기는 그리기만 한다.

import { SearchResultDraggable } from "./SearchResultDraggable";
import { HintIcon } from "./HintIcon";

export function SearchPanel({
  searchKeyword,
  setSearchKeyword,
  searchResults,
  searchListRef,
  handleSearchPlace,
  handleClearSearch,
  handlePlaceClick,
}) {
  return (
    <div className="panel">
      <h4 className="panel-title">
        카카오 장소 검색
        <HintIcon
          label="장소 검색 사용 안내"
          tip="장소를 검색한 뒤 마음에 드는 결과를 끌어다 후보 목록에 담아요. 계획표에는 후보 목록을 거쳐 올릴 수 있어요."
        />
      </h4>
      <div className="search-box">
        <form className="search-form" onSubmit={handleSearchPlace}>
          <input
            type="text"
            value={searchKeyword}
            onChange={(e) => setSearchKeyword(e.target.value)}
            placeholder="도시, 명소, 음식..."
          />
          {/* 결과가 있을 때만 초기화 — 목록·지도 핀을 한 번에 걷는다 */}
          {searchResults.length > 0 && (
            <button
              type="button"
              className="search-clear"
              onClick={handleClearSearch}
              title="검색 결과와 지도 핀을 지웁니다"
            >
              지우기
            </button>
          )}
          <button type="submit">검색</button>
        </form>

        {/* 💡 검색 결과 리스트: 버튼이 사라지고 이젠 꾹 눌러서 끌 수 있습니다! */}
        <div className="search-results" ref={searchListRef}>
          {searchResults.map((place) => (
            <SearchResultDraggable
              key={place.placeId}
              place={place}
              // 클릭 = 지도 이동 + 상세 말풍선 (드래그와 별개 동작)
              onClick={handlePlaceClick}
            />
          ))}
          {searchResults.length === 0 && (
            <div className="search-empty">
              검색 결과가 여기에 표시됩니다.
              <br />
              검색 후 패널을 왼쪽으로 끌어다 놓으세요.
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
