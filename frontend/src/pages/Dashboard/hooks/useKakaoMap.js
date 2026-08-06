// pages/Dashboard/hooks/useKakaoMap.js
//
// 카카오 지도와 장소 검색. 지도 전용 state·ref·effect를 전부 소유하고,
// 부모에는 "지도를 어떻게 쓰는지"만 노출한다(map 인스턴스 자체는 감춘다).
//
// SDK 로딩(중복 삽입·로딩 중 대기)은 addressLookup 의 ensureKakaoMaps 가 맡는다 —
// 블록 상세의 주소 검색도 같은 SDK 를 쓰므로 로더가 두 벌이면 서로의 <script> 를
// 기다리다 엇갈린다.

import { useState, useRef, useEffect, useCallback } from "react";
import { ensureKakaoMaps } from "../../../features/dashboard/map/addressLookup";
import * as placeApi from "../../../features/place/api/placeApi";
import { planPinImage, searchPinImage, ROUTE_LINE_COLOR } from "../mapPins";

export function useKakaoMap({ chains, items, activeDay, showToast }) {
  const [map, setMap] = useState(null);
  const [searchKeyword, setSearchKeyword] = useState("");
  const [searchResults, setSearchResults] = useState([]);
  const searchListRef = useRef(null);
  const infoWindowRef = useRef(null);
  // 검색 결과 핀 — 추적해 둬야 재검색·초기화 때 지도에서 걷을 수 있다
  const searchMarkersRef = useRef([]);
  // 검색 요청 세대 — SDK 콜백과 달리 HTTP 는 순서가 뒤집힐 수 있어, 늦게 도착한
  // 이전 응답이 최신 결과를 덮지 않도록 마지막 요청만 반영한다
  const searchSeqRef = useRef(0);

  // 지도 초기화 — 컨테이너 div 의 ref callback 으로 한다.
  //
  // "마운트 시 1회 effect + getElementById" 방식은 레이스가 있었다: 스냅샷 로딩
  // 가드가 null 을 반환하는 동안에는 컨테이너가 DOM 에 없어서, SDK 가 이미 로드된
  // 재진입(그룹 페이지에서 되돌아오기 등)에서는 initMap 이 빈손으로 끝나고 지도가
  // 영영 회색으로 남았다. ref callback 은 "div 가 실제로 마운트된 순간"에 불리므로
  // 레이스가 없고, 읽기 모드 전환으로 div 가 재마운트될 때도 다시 바인딩된다.
  const initMapOnContainer = useCallback((container) => {
    if (!container) {
      // 언마운트(읽기 모드 전환 등) — 카카오 지도는 destroy API 가 없어
      // 참조만 끊는다. 다음 마운트 때 새 인스턴스로 바인딩된다.
      setMap(null);
      return;
    }

    // SDK 로딩(중복 삽입·로딩 중 대기)은 addressLookup 이 맡는다 — 블록 상세의
    // 주소 검색도 같은 SDK 를 쓰므로 로더가 두 벌이면 서로의 <script> 를 기다리다 엇갈린다.
    ensureKakaoMaps()
      .then((maps) => {
        // 늦게 도착했는데 그 사이 컨테이너가 떨어져 나갔으면 버린다
        if (!container.isConnected) return;
        setMap(
          new maps.Map(container, {
            center: new maps.LatLng(33.450701, 126.570667),
            level: 7,
          }),
        );
      })
      .catch(() => {
        // 지도는 보조 기능이라 실패해도 보드는 그대로 쓴다 (회색 박스로 남는다)
      });
  }, []);

  // 지도 패널이 사이드 폭을 그대로 쓰게 되면서(빈 공간 활용) 창 크기에 따라 실제
  // 픽셀 크기가 바뀐다 — 카카오 지도는 컨테이너 크기가 변하면 relayout() 을 불러줘야
  // 타일과 중심이 어긋나지 않는다.
  useEffect(() => {
    if (!map) return;
    const onResize = () => map.relayout();
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, [map]);

  // ── 계획표 블록 → 지도 핀 (QA 배치3) ──
  // 활성 Day 체인의 좌표 있는 블록을 핀으로 찍는다. 핀은 편집을 따라 실시간으로
  // 갱신하되, 카메라 이동(범위 맞춤)은 "지도 준비·Day 전환 때 한 번"만 한다 —
  // 블록을 만질 때마다 지도가 움직이면 검색하려고 옮겨 둔 화면을 뺏는다.
  const chainMarkersRef = useRef([]);
  const routeLinesRef = useRef([]);
  const lastMapFitRef = useRef(null); // { map, day } — 카메라를 이미 맞춘 조합
  useEffect(() => {
    if (!map || !window.kakao?.maps) return;

    chainMarkersRef.current.forEach((m) => m.setMap(null));
    chainMarkersRef.current = [];
    routeLinesRef.current.forEach((l) => l.setMap(null));
    routeLinesRef.current = [];

    const chainPoints = (chains[activeDay] || [])
      .map((id) => items[id])
      .filter((it) => it?.lat != null && it?.lng != null);

    chainPoints.forEach((it, idx) => {
      const position = new window.kakao.maps.LatLng(it.lat, it.lng);
      const marker = new window.kakao.maps.Marker({
        map,
        position,
        title: it.name,
        // 초록 + 방문 순번 = 이미 일정에 넣은 곳 (검색 결과는 번호 없는 파랑).
        // 순번은 그날 좌표 있는 블록 기준 — 좌표 없는 교통 블록은 건너뛴다.
        // zIndex 로 검색 핀 위에 둔다: 같은 자리에 겹쳐도 계획이 가려지지 않는다.
        image: planPinImage(idx + 1),
        zIndex: 5,
      });
      // 핀 클릭 = 검색 결과 클릭과 같은 상세 말풍선
      window.kakao.maps.event.addListener(marker, "click", () => {
        if (!infoWindowRef.current) {
          infoWindowRef.current = new window.kakao.maps.InfoWindow({
            zIndex: 1,
            removable: true,
          });
        }
        infoWindowRef.current.setContent(
          `<div style="padding:12px;font-size:13px;color:#333;min-width:180px;">
             <b style="display:block;margin-bottom:4px;color:#d97e3c;">${it.name}</b>
             ${it.address ? `<span>${it.address}</span>` : ""}
           </div>`,
        );
        infoWindowRef.current.setPosition(position);
        infoWindowRef.current.open(map);
      });
      chainMarkersRef.current.push(marker);
    });

    // ── 이동 경로 선 ──
    // 교통 블록이 낀 구간만 잇는다 — 단순히 이웃한 두 장소를 잇는 게 아니라
    // "이동 수단을 정해 둔 구간"만 그려야 계획한 동선과 아직 빈 구간이 구분된다.
    // 교통 블록 자체에는 좌표가 없으므로(경로 조회 결과에 legs 의 정거장 '이름'만
    // 오고 좌표는 없다) 앞뒤 장소를 직선으로 잇는다 — 실제 도로·선로 모양이 아니다.
    const chainItems = (chains[activeDay] || [])
      .map((id) => items[id])
      .filter(Boolean);
    const hasCoords = (it) => it?.lat != null && it?.lng != null;

    chainItems.forEach((it, i) => {
      if (it.cat !== "trans") return;

      let from = null;
      for (let k = i - 1; k >= 0; k -= 1) {
        if (hasCoords(chainItems[k])) {
          from = chainItems[k];
          break;
        }
      }
      let to = null;
      for (let k = i + 1; k < chainItems.length; k += 1) {
        if (hasCoords(chainItems[k])) {
          to = chainItems[k];
          break;
        }
      }
      if (!from || !to) return; // 한쪽 끝의 좌표를 모르면 그릴 수 없다

      const line = new window.kakao.maps.Polyline({
        map,
        path: [
          new window.kakao.maps.LatLng(from.lat, from.lng),
          new window.kakao.maps.LatLng(to.lat, to.lng),
        ],
        strokeWeight: 4,
        strokeColor: ROUTE_LINE_COLOR,
        strokeOpacity: 0.75,
        // 실제 경로가 아니라 "이 두 곳을 이동한다"는 표시라 점선으로 둔다
        strokeStyle: "shortdash",
      });
      routeLinesRef.current.push(line);
    });

    // 카메라 맞춤 — 이 (지도, Day) 조합에서 아직 안 맞췄을 때만.
    // 활성 Day 에 좌표가 없으면 배치된 첫 여행지(지도 시작점)라도 보여준다.
    const last = lastMapFitRef.current;
    if (last?.map === map && last?.day === activeDay) return;

    let fitPoints = chainPoints;
    if (fitPoints.length === 0) {
      const firstPlaced = Object.values(chains)
        .flat()
        .map((id) => items[id])
        .find((it) => it?.lat != null && it?.lng != null);
      fitPoints = firstPlaced ? [firstPlaced] : [];
    }
    // 맞출 좌표가 아직 없으면 "맞췄다"고 기록하지 않는다 — 빈 보드로 들어온 직후
    // 시작 지점 블록이 뒤늦게 생기는 경우(부트스트랩), 기록을 먼저 해 버리면
    // 그 블록이 생겨도 카메라가 영영 안 움직인다(여수 미이동 버그의 원인).
    if (fitPoints.length === 0) return;
    lastMapFitRef.current = { map, day: activeDay };
    if (fitPoints.length === 1) {
      map.setLevel(5);
      map.setCenter(
        new window.kakao.maps.LatLng(fitPoints[0].lat, fitPoints[0].lng),
      );
    } else {
      const bounds = new window.kakao.maps.LatLngBounds();
      fitPoints.forEach((it) =>
        bounds.extend(new window.kakao.maps.LatLng(it.lat, it.lng)),
      );
      map.setBounds(bounds);
    }
  }, [map, chains, activeDay, items]);

  /**
   * 그 장소로 카메라를 옮기고 말풍선을 띄운다. 좌표가 없는 블록(교통·기타)은
   * 옮길 곳이 없으므로 아무것도 하지 않는다.
   *
   * 카메라 자동 맞춤(lastMapFitRef)과 달리 이건 사용자가 직접 누른 결과라
   * "화면을 뺏는다"는 문제가 없다.
   */
  const focusPlace = useCallback(
    (item) => {
      if (!map || !window.kakao?.maps) return;
      if (item?.lat == null || item?.lng == null) return;

      const position = new window.kakao.maps.LatLng(item.lat, item.lng);
      // 너무 멀리 있으면 당겨 준다 — 이미 가까우면 지금 배율을 그대로 둔다
      if (map.getLevel() > 5) map.setLevel(5);
      map.panTo(position);

      if (!infoWindowRef.current) {
        infoWindowRef.current = new window.kakao.maps.InfoWindow({
          zIndex: 1,
          removable: true,
        });
      }
      infoWindowRef.current.setContent(
        `<div style="padding:12px;font-size:13px;color:#333;min-width:180px;">
           <b style="display:block;margin-bottom:4px;color:#d97e3c;">${item.name ?? ""}</b>
           ${item.address ? `<span>${item.address}</span>` : ""}
         </div>`,
      );
      infoWindowRef.current.setPosition(position);
      infoWindowRef.current.open(map);
    },
    [map],
  );

  // 장소 검색은 서버를 거친다 — 브라우저에서 카카오를 직접 부르지 않는다.
  // 지도 렌더링(마커·말풍선)만 SDK 를 계속 쓴다.
  const handleSearchPlace = async (e) => {
    e.preventDefault();
    const keyword = searchKeyword.trim();
    if (!keyword) return;

    const seq = ++searchSeqRef.current;
    let results;
    try {
      // 지도 중심을 주면 그 지점 기준 거리순으로 정렬된다(명세 MAP-02)
      const center = map?.getCenter();
      results = await placeApi.searchPlaces(keyword, {
        lat: center?.getLat(),
        lng: center?.getLng(),
      });
    } catch (err) {
      if (seq !== searchSeqRef.current) return; // 이미 다음 검색이 시작됐다
      showToast?.(err?.message ?? "검색 중 오류가 발생했어요.");
      return;
    }
    if (seq !== searchSeqRef.current) return; // 늦게 온 이전 응답은 버린다

    setSearchResults(results);

    // 새 검색을 하면 결과 목록 스크롤을 맨 위로 올리고, 열려 있던 말풍선을 닫는다
    if (searchListRef.current) searchListRef.current.scrollTop = 0;
    infoWindowRef.current?.close();

    // 결과가 없으면 목록의 빈 상태(SearchPanel 의 search-empty)가 알려준다 —
    // 검색 결과 없음은 에러가 아니므로 토스트를 띄우지 않는다
    if (!map || results.length === 0) return;

    // 이전 검색의 핀부터 걷는다 — 안 걷으면 검색할 때마다 지도에 쌓인다
    searchMarkersRef.current.forEach((m) => m.setMap(null));
    searchMarkersRef.current = [];

    const bounds = new window.kakao.maps.LatLngBounds();
    results.forEach((place) => {
      const position = new window.kakao.maps.LatLng(place.lat, place.lng);
      bounds.extend(position);

      const marker = new window.kakao.maps.Marker({
        map,
        position,
        title: place.name,
        // 파랑 = 아직 후보 (타임라인에 들어간 블록은 초록)
        image: searchPinImage(),
        zIndex: 3,
      });
      // 마커 클릭 = 상세 말풍선
      window.kakao.maps.event.addListener(marker, "click", () => {
        handlePlaceClick(place);
      });
      searchMarkersRef.current.push(marker);
    });

    map.setBounds(bounds);
  };
  // 검색 내역 초기화 (QA) — 결과 목록·지도 핀·말풍선·입력어를 한 번에 걷는다
  const handleClearSearch = () => {
    setSearchResults([]);
    setSearchKeyword("");
    searchMarkersRef.current.forEach((m) => m.setMap(null));
    searchMarkersRef.current = [];
    infoWindowRef.current?.close();
  };

  const handlePlaceClick = (place) => {
    if (map && window.kakao && window.kakao.maps) {
      const moveLatLon = new window.kakao.maps.LatLng(place.lat, place.lng);

      map.setLevel(4);
      map.panTo(moveLatLon);

      // 💡 2-1. 인포윈도우가 아직 안 만들어졌다면 최초 1회 생성
      if (!infoWindowRef.current) {
        infoWindowRef.current = new window.kakao.maps.InfoWindow({
          zIndex: 1,
          removable: true, // 창 닫기(X) 버튼 활성화
        });
      }

      // 💡 2-2. 정보 창 안에 들어갈 디자인(HTML) 구성
      // 현재 앱의 테마 색상(#d97e3c 등)을 사용해 통일감을 주었습니다.
      const content = `
        <div style="padding:15px; font-size:13px; color:#333; min-width:200px; border-radius:8px;">
          <b style="font-size:15px; display:block; margin-bottom:5px; color:#d97e3c;">${place.name}</b>
          ${place.address ? `<span style="display:block;">${place.address}</span>` : ""}
          ${place.phone ? `<span style="display:block; margin-top:5px; color:#6b7fc7;">📞 ${place.phone}</span>` : ""}
        </div>
      `;

      // 💡 2-3. 내용과 좌표를 갱신하고 지도에 열기
      infoWindowRef.current.setContent(content);
      infoWindowRef.current.setPosition(moveLatLon);
      infoWindowRef.current.open(map);
    }
  };

  // ── 지도에 핀 직접 찍기 (MAP-04) ──
  // 검색으로 안 나오는 곳을 지도 클릭 한 번으로 잡는다. 훅은 "클릭 한 번을
  // 좌표+주소로 돌려주는 약속"만 내주고, 모달을 감췄다 되살리는 건 부모가 맡는다.
  const [pinPickMode, setPinPickMode] = useState(false);
  const tempPinRef = useRef(null);
  const pinPickResolveRef = useRef(null);

  // 대기 중인 약속을 반드시 결말짓고 임시 핀을 걷는다 — 안 그러면 버튼을 두 번
  // 누른 순간 먼저 만든 Promise 가 영원히 pending 으로 남아, 그걸 await 하던
  // 호출부까지 같이 멈춘다.
  const settlePinPick = useCallback((value) => {
    tempPinRef.current?.setMap(null);
    tempPinRef.current = null;
    const resolve = pinPickResolveRef.current;
    pinPickResolveRef.current = null;
    resolve?.(value);
  }, []);

  /** 지도 클릭 한 번을 { lat, lng, address } 로 돌려준다. 취소하면 null 이다 */
  const startPinPick = useCallback(() => {
    // 이미 지정을 기다리는 중이면(버튼 연타) 앞선 약속을 취소로 닫고 새로 받는다
    settlePinPick(null);
    setPinPickMode(true);
    return new Promise((resolve) => {
      pinPickResolveRef.current = resolve;
    });
  }, [settlePinPick]);

  const cancelPinPick = useCallback(() => {
    setPinPickMode(false);
    settlePinPick(null);
  }, [settlePinPick]);

  // 지정 모드일 때만 지도 클릭·Esc 리스너를 붙인다
  useEffect(() => {
    if (!map || !pinPickMode || !window.kakao?.maps) return undefined;

    // 이 지정 세션이 이미 끝났는지 표시한다. 역지오코딩을 기다리는 사이 취소되면
    // 그 사이 시작된 "다음" 세션의 약속을 뒤늦게 온 응답이 취소된 좌표로 채워
    // 버린다 — A 블록에서 찍고 Esc → B 블록에서 지정 시작 → B 폼에 A 의 좌표가
    // 꽂히는 식이다. 정리(cleanup)는 지정 모드가 꺼질 때마다 도므로,
    // settle 된 세션의 클로저는 전부 여기서 stale 로 찍힌다.
    let stale = false;

    const onClick = async (mouseEvent) => {
      const latlng = mouseEvent.latLng;
      const lat = latlng.getLat();
      const lng = latlng.getLng();

      // 역지오코딩을 기다리는 동안 찍은 자리를 보여 준다(끝나면 걷는다)
      tempPinRef.current?.setMap(null);
      tempPinRef.current = new window.kakao.maps.Marker({
        map,
        position: latlng,
        image: searchPinImage(),
        zIndex: 5,
      });

      // 주소를 못 얻어도 좌표는 유효하다 — 주소만 비우고 사용자가 직접 쓰게 둔다
      let address;
      try {
        const found = await placeApi.reverseGeocode(lat, lng);
        address = found?.roadAddress || found?.address || "";
      } catch {
        address = "";
      }

      // 기다리는 사이 이 세션이 끝났으면(취소·모달 닫힘) 아무것도 하지 않는다.
      // 임시 핀은 그 취소가 이미 걷었다.
      if (stale) return;

      setPinPickMode(false);
      settlePinPick({ lat, lng, address });
    };

    const onKeyDown = (e) => {
      if (e.key !== "Escape") return;
      // 지정 중의 Esc 는 지정 취소만 한다 — 같은 Esc 가 다른 닫기 핸들러까지
      // 타고 가면 한 번 누른 걸로 두 가지가 닫힌다. capture 로 먼저 잡아 끊는다.
      e.stopPropagation();
      e.preventDefault();
      cancelPinPick();
    };

    window.kakao.maps.event.addListener(map, "click", onClick);
    document.addEventListener("keydown", onKeyDown, true);
    return () => {
      stale = true;
      window.kakao.maps.event.removeListener(map, "click", onClick);
      document.removeEventListener("keydown", onKeyDown, true);
    };
  }, [map, pinPickMode, cancelPinPick, settlePinPick]);

  // MAP 모드 챗봇에 넘길 지도 뷰포트 (남서·북동) — 지도가 아직 없으면 null(위젯이 안내)
  const getMapBounds = () => {
    if (!map) return null;
    const bounds = map.getBounds();
    const sw = bounds.getSouthWest();
    const ne = bounds.getNorthEast();
    return {
      swLat: sw.getLat(),
      swLng: sw.getLng(),
      neLat: ne.getLat(),
      neLng: ne.getLng(),
    };
  };

  return {
    initMapOnContainer,
    searchKeyword,
    setSearchKeyword,
    searchResults,
    searchListRef,
    handleSearchPlace,
    handleClearSearch,
    handlePlaceClick,
    focusPlace,
    getMapBounds,
    pinPickMode,
    startPinPick,
    cancelPinPick,
  };
}
