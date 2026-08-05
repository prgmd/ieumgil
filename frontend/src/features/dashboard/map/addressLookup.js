/**
 * 주소 입력 배관 — 도로명 주소 검색(다음 우편번호) + 좌표 변환(카카오 지오코딩).
 *
 * 커스텀 블록은 카카오 장소 검색을 거치지 않아 좌표가 없다. 그런데 장소성
 * 카테고리(SPOT·FOOD·STAY)는 서버가 lat/lng 를 필수로 본다(BLOCK400) — 그래서
 * "주소를 고르면 좌표까지 따라오는" 경로가 필요하다:
 *
 *   openAddressSearch()  도로명 주소 팝업에서 주소 고르기
 *        ↓
 *   geocodeAddress()     그 주소를 카카오 지오코딩으로 좌표(lat/lng)로
 *        ↓
 *   createBlock()        좌표까지 실어 블록 생성
 *
 * 외부 스크립트 두 개(카카오 지도 SDK·다음 우편번호)를 다루므로 로딩을
 * 여기 한곳에 모은다 — 화면 곳곳에서 <script> 를 직접 붙이면 중복 삽입과
 * "붙였는데 아직 로딩 중"인 레이스를 매번 다시 만들게 된다.
 */

// 지도 SDK 키 — JS 키(도메인 등록 기준)다. 로그인에 쓰는 REST 키(.env)와 다르다.
const KAKAO_APP_KEY = "71b94eabee0913242230da390f4d20f2";
const KAKAO_SCRIPT_ID = "kakao-map-script";
const KAKAO_SRC = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${KAKAO_APP_KEY}&autoload=false&libraries=services`;

const POSTCODE_SCRIPT_ID = "daum-postcode-script";
const POSTCODE_SRC =
  "https://t1.daumcdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js";

/**
 * 같은 스크립트를 두 번 붙이지 않고, 이미 붙어 있으면 그 로드를 기다린다.
 * 이미 로드가 끝난 태그는 load 이벤트가 다시 오지 않으므로 data-loaded 로 표시해 둔다
 * (표시가 없으면 "영영 기다리는" 프라미스가 된다).
 */
function loadScriptOnce(id, src) {
  return new Promise((resolve, reject) => {
    const fail = (script) => {
      // 실패한 태그를 남겨 두면 다음 호출이 existing 분기로 들어가 이미 끝난
      // 이벤트에 리스너를 붙이게 된다(영영 대기) — 지워서 다음 호출이 새로 삽입하게 한다.
      script.remove();
      reject(new Error("외부 스크립트를 불러오지 못했어요."));
    };

    const existing = document.getElementById(id);
    if (existing) {
      if (existing.dataset.loaded === "true") {
        resolve();
        return;
      }
      existing.addEventListener("load", () => resolve(), { once: true });
      existing.addEventListener("error", () => fail(existing), {
        once: true,
      });
      return;
    }

    const script = document.createElement("script");
    script.id = id;
    script.src = src;
    script.async = true;
    script.addEventListener(
      "load",
      () => {
        script.dataset.loaded = "true";
        resolve();
      },
      { once: true },
    );
    script.addEventListener("error", () => fail(script), { once: true });
    document.head.appendChild(script);
  });
}

/**
 * 카카오 지도 SDK 를 services 라이브러리까지 준비된 상태로 보장한다.
 * autoload=false 로 받으므로 스크립트 로드 후 maps.load() 까지 끝나야 쓸 수 있다.
 * @returns {Promise<object>} window.kakao.maps
 */
export function ensureKakaoMaps() {
  if (window.kakao?.maps?.services) return Promise.resolve(window.kakao.maps);

  return loadScriptOnce(KAKAO_SCRIPT_ID, KAKAO_SRC).then(
    () =>
      new Promise((resolve) => {
        window.kakao.maps.load(() => resolve(window.kakao.maps));
      }),
  );
}

/**
 * 우편번호 스크립트를 미리 받아 둔다 — 폼이 열릴 때 호출한다.
 *
 * 버튼을 누른 뒤에 스크립트를 받으면, 그 await 동안 브라우저의 "사용자 조작으로
 * 열린 창"(transient activation) 자격이 만료돼 팝업이 차단될 수 있다. 미리 받아
 * 두면 클릭 → 즉시 open 이라 차단되지 않는다.
 * 실패는 무시한다 — 실제로 필요한 시점에 openAddressSearch 가 다시 시도하고,
 * 그때 사용자에게 알린다.
 */
export function preloadAddressSearch() {
  loadScriptOnce(POSTCODE_SCRIPT_ID, POSTCODE_SRC).catch(() => {});
}

/**
 * 도로명 주소 검색 팝업을 띄우고 사용자가 고른 주소를 돌려준다.
 *
 * oncomplete 가 아니라 onclose 에서 resolve 한다 — 주소를 골라도, 그냥 창을 닫아도
 * onclose 는 반드시 한 번 불리므로 "고르지 않고 닫음"이 영영 안 끝나는 프라미스가
 * 되지 않는다.
 *
 * @returns {Promise<{roadAddress, jibunAddress, zonecode, buildingName}|null>}
 *          고르지 않고 닫으면 null
 */
export async function openAddressSearch() {
  await loadScriptOnce(POSTCODE_SCRIPT_ID, POSTCODE_SRC);

  return new Promise((resolve) => {
    let picked = null;

    new window.daum.Postcode({
      oncomplete: (data) => {
        // 신축 등으로 확정 주소가 없으면 auto* 에 예상 주소가 온다
        picked = {
          roadAddress: data.roadAddress || data.autoRoadAddress || "",
          jibunAddress: data.jibunAddress || data.autoJibunAddress || "",
          zonecode: data.zonecode || "",
          buildingName: data.buildingName || "",
        };
      },
      onclose: () => resolve(picked),
    }).open();
  });
}

/**
 * 카카오 키워드 장소 검색 — 프로젝트 생성 폼의 출발지점 선택처럼 지도 화면이
 * 없는 곳에서도 쓸 수 있게 SDK 로딩까지 여기서 책임진다.
 * @returns {Promise<Array<{placeId, name, address, lat, lng}>>} 상위 5건 (없으면 빈 배열)
 */
export async function searchPlaces(keyword) {
  const maps = await ensureKakaoMaps();

  return new Promise((resolve, reject) => {
    new maps.services.Places().keywordSearch(keyword, (data, status) => {
      if (status === maps.services.Status.ZERO_RESULT) {
        resolve([]);
        return;
      }
      if (status !== maps.services.Status.OK) {
        reject(
          new Error("장소를 검색하지 못했어요. 잠시 후 다시 시도해주세요."),
        );
        return;
      }
      resolve(
        data.slice(0, 5).map((p) => ({
          placeId: String(p.id),
          name: p.place_name,
          address: p.road_address_name || p.address_name || "",
          lat: Number(p.y),
          lng: Number(p.x),
        })),
      );
    });
  });
}

/**
 * 주소 → 좌표. 카카오 응답은 x=경도·y=위도이고 값이 문자열이다(헷갈리기 쉬운 지점).
 * @returns {Promise<{lat:number, lng:number, roadAddress:string, jibunAddress:string}>}
 * @throws  좌표를 못 찾으면 사용자에게 그대로 보여줄 수 있는 메시지로 던진다
 */
export async function geocodeAddress(address) {
  const maps = await ensureKakaoMaps();

  return new Promise((resolve, reject) => {
    new maps.services.Geocoder().addressSearch(address, (result, status) => {
      if (status !== maps.services.Status.OK || !result?.length) {
        reject(new Error("이 주소로는 좌표를 찾지 못했어요."));
        return;
      }
      const top = result[0];
      resolve({
        lat: Number(top.y),
        lng: Number(top.x),
        roadAddress: top.road_address?.address_name ?? "",
        jibunAddress: top.address?.address_name ?? "",
      });
    });
  });
}
