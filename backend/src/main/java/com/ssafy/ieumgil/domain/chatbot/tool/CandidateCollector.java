package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.festival.entity.Festival;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * tool 실행 결과를 응답의 candidates[]로 모은다 (BOT-04).
 *
 * <p>요청마다 새로 만들어 tool 생성자로 넘긴다. tool이 이미 요청 단위로 생성되므로
 * ThreadLocal이나 @RequestScope 없이 인자 전달만으로 충분하다 — Spring AI가 tool을
 * 어느 스레드에서 실행하든 무관하다.
 *
 * <p>LLM에 넘기는 요약(PlaceSearchSummary·FestivalSummary)에는 좌표가 없다. 좌표를 모델에
 * 노출하지 않기로 한 기존 결정 때문이며, 그래서 수집기는 원본 도메인 객체를 받아
 * 블록 생성에 필요한 필드를 채운다. 즉 수집기가 모델보다 풍부한 데이터를 본다.
 */
@Slf4j
public class CandidateCollector {

    /** 같은 장소가 여러 번 검색돼도 하나만 남기고 처음 등장 순서를 지킨다(모델이 언급한 순서와 맞음) */
    private final Map<String, ChatbotResDTO.Candidate> byKey = new LinkedHashMap<>();

    public void addPlace(PlaceResDTO.Place place) {
        if (place.lat() == null || place.lng() == null) {
            log.warn("좌표 없는 장소를 후보에서 제외: placeId={}", place.placeId());
            return;
        }
        put(BlockSource.KAKAO, place.placeId(), ChatbotResDTO.Candidate.builder()
                .name(place.name())
                .category(toBlockCategory(place.category()))
                .lat(place.lat())
                .lng(place.lng())
                .address(place.address())
                .placeId(place.placeId())
                .source(BlockSource.KAKAO)
                .subCategory(place.category())
                .build());
    }

    public void addFestival(Festival festival) {
        // 장소성 블록은 좌표가 필수다. 좌표 없이 내보내면 프론트가 블록 생성에서 BLOCK400을 맞는다.
        // TourAPI mapx/mapy가 빈 경우 수집 배치가 null로 저장하므로 방어가 필요하다.
        // 실측(수집 100건)으로는 누락 0건이라, 이 경고가 찍히면 정상이 아니라는 신호다.
        if (festival.getLat() == null || festival.getLng() == null) {
            log.warn("좌표 없는 축제를 후보에서 제외: contentId={}, title={}",
                    festival.getContentId(), festival.getTitle());
            return;
        }
        put(BlockSource.BOT, festival.getContentId(), ChatbotResDTO.Candidate.builder()
                .name(festival.getTitle())
                .category(BlockCategory.SPOT)
                .lat(festival.getLat())
                .lng(festival.getLng())
                .address(festival.getAddr())
                .placeId(festival.getContentId())
                .source(BlockSource.BOT)
                .subCategory(toFestivalLabel(festival.getCategory()))
                .eventStartDate(festival.getEventStartDate().toString())
                .eventEndDate(festival.getEventEndDate().toString())
                // 블록에는 기간 컬럼이 없어 자유텍스트로만 남길 수 있다. 서버가 문구를 만들어
                // 넘기면 프론트엔드가 블록 생성 시 그대로 detail에 실으면 된다.
                .detail("개최 기간: %s ~ %s".formatted(
                        festival.getEventStartDate(), festival.getEventEndDate()))
                .build());
    }

    public List<ChatbotResDTO.Candidate> candidates() {
        return List.copyOf(byKey.values());
    }

    /** 출처를 키에 섞는다 — 카카오 place id와 축제 contentId가 우연히 같아도 서로를 지우지 않는다 */
    private void put(BlockSource source, String id, ChatbotResDTO.Candidate candidate) {
        byKey.putIfAbsent(source.name() + ":" + id, candidate);
    }

    /**
     * 카카오 카테고리 그룹명을 블록 카테고리로 접는다.
     * 프론트엔드는 같은 판단을 category_group_code로 한다(FD6·CE7→food, AD5→stay, 그 외 spot).
     * 우리는 group_name을 받으므로 대응하는 이름으로 매핑한다 — 카페=CE7, 음식점=FD6, 숙박=AD5.
     * 카테고리가 아예 빈 장소도 실제로 존재하므로 기본값은 SPOT이다.
     * {@link PlaceRanker}도 "계획에 이미 있는 종류"를 판정할 때 같은 접기를 써야 해서 static 이다 —
     * 두 곳이 각자 매핑하면 카드의 카테고리와 랭킹의 판단이 조용히 어긋난다.
     */
    static BlockCategory toBlockCategory(String kakaoCategoryGroupName) {
        if (kakaoCategoryGroupName == null) {
            return BlockCategory.SPOT;
        }
        return switch (kakaoCategoryGroupName) {
            case "카페", "음식점" -> BlockCategory.FOOD;
            case "숙박" -> BlockCategory.STAY;
            default -> BlockCategory.SPOT;
        };
    }

    private String toFestivalLabel(String rawCategory) {
        return switch (rawCategory) {
            case "EV01" -> "축제";
            case "EV02" -> "공연";
            default -> "행사";
        };
    }
}
