import { describe, it, expect } from "vitest";
import { buildKakaoPlaceUrl, buildKakaoSearchUrl } from "../externalLink";

describe("buildKakaoPlaceUrl", () => {
  it("정상 placeId는 그대로 딥링크가 된다", () => {
    expect(buildKakaoPlaceUrl("12345")).toBe(
      "https://place.map.kakao.com/12345",
    );
  });

  it("placeId의 위험 문자를 인코딩해 속성 탈출을 막는다", () => {
    // 저장된 placeId가 `123" onmouseover="alert(1)` 이어도
    // 큰따옴표가 URL 인코딩돼 href 속성을 빠져나오지 못한다
    const url = buildKakaoPlaceUrl('123" onmouseover="alert(1)');
    expect(url).not.toContain('"');
    expect(url).toContain("%22");
  });
});

describe("buildKakaoSearchUrl", () => {
  it("검색어를 인코딩한다", () => {
    expect(buildKakaoSearchUrl('a" b')).toContain("%22");
  });
});
