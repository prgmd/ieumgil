package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 장소명을 좌표로 바꾼다. 도보·택시 경로 tool이 공유한다.
 *
 * <p><b>보드를 먼저 본다.</b> 사용자가 경로를 묻는 대상은 대개 이미 일정에 올려둔 블록이고
 * 그 블록에는 실좌표가 있다. 그런데도 이름으로 카카오를 다시 검색하면 홉이 하나 늘고
 * (보드 조회 → 이름 → 카카오 검색 → 좌표 → 경로), 동명 장소나 카카오에 없는 이름에서 끊긴다.
 * 실제로 그렇게 끊겼을 때 모델이 직선거리를 지어내는 것을 실측했다.
 *
 * <p>보드 조회는 <b>지연·1회</b>다. 경로 tool이 실제로 호출될 때만 읽으므로 보드를 보지 않는
 * 대다수 대화에는 쿼리가 나가지 않는다 — 보드를 상시 주입하지 않고 tool로 분리한 이유와 같다.
 * 출발·도착을 각각 resolve해도 조회는 한 번이다.
 */
public class KakaoPlaceCoordinateResolver {

    private final PlaceQueryService placeQueryService;
    private final Supplier<List<Block>> boardSupplier;

    private List<Block> cachedBoard;

    public KakaoPlaceCoordinateResolver(PlaceQueryService placeQueryService) {
        this(placeQueryService, List::of);
    }

    public KakaoPlaceCoordinateResolver(PlaceQueryService placeQueryService,
                                        Supplier<List<Block>> boardSupplier) {
        this.placeQueryService = placeQueryService;
        this.boardSupplier = boardSupplier;
    }

    /** 보드에 같은 이름의 블록이 있으면 그 좌표를, 없으면 목적지+장소명으로 카카오를 검색한다. */
    public Optional<PlaceResDTO.Place> resolve(String destination, String placeName) {
        Optional<PlaceResDTO.Place> onBoard = findOnBoard(placeName);
        if (onBoard.isPresent()) {
            return onBoard;
        }
        String query = destination + " " + placeName;
        return placeQueryService.searchPlaces(query, null, null).stream().findFirst();
    }

    private Optional<PlaceResDTO.Place> findOnBoard(String placeName) {
        if (placeName == null || placeName.isBlank()) {
            return Optional.empty();
        }
        String needle = placeName.trim();
        return board().stream()
                // 좌표 없는 블록은 쓸 수 없다 — 카카오 폴백으로 보낸다
                .filter(block -> block.getLat() != null && block.getLng() != null)
                .filter(block -> matches(block.getName(), needle))
                .findFirst()
                .map(this::toPlace);
    }

    /** 모델은 보드 tool이 준 이름을 그대로 넘기므로 완전 일치가 기본이고, 수식어가 붙은 경우만 포함 관계로 본다. */
    private boolean matches(String blockName, String needle) {
        if (blockName == null) {
            return false;
        }
        String name = blockName.trim();
        return name.equalsIgnoreCase(needle) || name.contains(needle) || needle.contains(name);
    }

    private List<Block> board() {
        if (cachedBoard == null) {
            cachedBoard = boardSupplier.get();
        }
        return cachedBoard;
    }

    private PlaceResDTO.Place toPlace(Block block) {
        return PlaceResDTO.Place.builder()
                .placeId(block.getPlaceId())
                .name(block.getName())
                .address(block.getAddress())
                .lat(block.getLat().doubleValue())
                .lng(block.getLng().doubleValue())
                .category(block.getSubCategory())
                .build();
    }
}
