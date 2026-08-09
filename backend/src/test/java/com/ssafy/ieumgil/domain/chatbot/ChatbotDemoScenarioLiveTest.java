package com.ssafy.ieumgil.domain.chatbot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.chatbot.config.WebSearchInterceptor;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.chatbot.service.ChatbotCommandService;
import com.ssafy.ieumgil.domain.festival.repository.FestivalRepository;
import com.ssafy.ieumgil.domain.festival.service.FestivalQueryService;
import com.ssafy.ieumgil.domain.group.entity.GroupMember;
import com.ssafy.ieumgil.domain.group.entity.TravelGroup;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.entity.TransportPref;
import com.ssafy.ieumgil.domain.transit.service.BusScheduleProvider;
import com.ssafy.ieumgil.domain.transit.service.FlightScheduleProvider;
import com.ssafy.ieumgil.domain.transit.service.TrainScheduleProvider;
import com.ssafy.ieumgil.domain.transit.util.Haversine;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * 데모 시나리오 10건을 <b>실제 진입점</b>({@link ChatbotCommandService#sendMessage})으로 한 번씩 돌린다.
 *
 * <p>기존 live 테스트는 전부 카카오를 mock으로 막고 tool 파이프라인을 손으로 조립했다. 그래서
 * <b>실제 카카오 결과 15건 → 재정렬 → 8건 절단</b>, <b>실블록이 앵커가 되는 거리 신호</b>,
 * <b>계획 카테고리 페널티</b>, 그리고 <b>서비스 자체</b>가 한 번도 함께 돌아본 적이 없다.
 * 이 테스트가 그 구간을 실데이터로 한 번에 지난다.
 *
 * <p><b>이것은 게이트가 아니라 검증 도구다.</b> 단언은 다툼의 여지가 없는 것만 건다
 * (예외 없음·빈 답변 아님·좌표 있음·MAP 카드는 뷰포트 안). 답변 품질·카드 수·표현은
 * 단언하지 않고 사람이 읽을 리포트로 남긴다. 한 시나리오가 터져도 나머지는 계속 돈다.
 *
 * <p>외부 API는 하나도 막지 않는다. tool 호출 추적은 spy로 하되 {@code callRealMethod()}로
 * 실동작을 그대로 통과시킨다 — 관찰만 하고 행동은 바꾸지 않는다.
 */
@Tag("live")
class ChatbotDemoScenarioLiveTest extends IntegrationTestSupport {

    /** 데모 시나리오: 부산 1박2일, 2인 */
    private static final LocalDate TRIP_START = LocalDate.now().plusDays(5);
    private static final LocalDate TRIP_END = TRIP_START.plusDays(1);

    /** 해운대 일대 — MAP 모드 뷰포트 */
    private static final ChatbotReqDTO.MapContext HAEUNDAE_VIEWPORT =
            new ChatbotReqDTO.MapContext(35.155, 129.150, 35.172, 129.180);

    /** 전국 체인 — 상위권에 올라왔는지 리포트에 표시하려고 둔다(단언하지 않는다) */
    private static final List<String> FRANCHISE_BRANDS = List.of(
            "스타벅스", "투썸", "이디야", "메가MGC", "메가커피", "컴포즈", "빽다방",
            "파스쿠찌", "할리스", "커피빈", "탐앤탐스", "더벤티", "매머드",
            "맥도날드", "버거킹", "롯데리아", "KFC", "맘스터치", "서브웨이",
            "파리바게뜨", "뚜레쥬르", "배스킨라빈스", "던킨", "공차", "스무디킹");

    private static final Pattern REASON_PATTERN =
            Pattern.compile("도보\\s*\\d+\\s*분|체인점이 아닌|지금 일정에 없는 종류");

    /**
     * {@link IntegrationTestSupport}가 비워 둔 TourAPI 키를 실제 값으로 되돌린다.
     * 축제 시나리오가 실데이터를 봐야 하므로 이 테스트에서만 되살린다 —
     * {@code @DynamicPropertySource}는 인라인 프로퍼티보다 우선순위가 높다.
     */
    @DynamicPropertySource
    static void realTourApiKey(DynamicPropertyRegistry registry) {
        registry.add("tourapi.service-key", () -> dotenv("TOUR_API_KEY"));
    }

    @Autowired
    ChatbotCommandService chatbotCommandService;
    @Autowired
    FestivalRepository festivalRepository;
    /**
     * <b>spy를 걸지 않는다.</b> Spring Data 리포지토리 빈은 인터페이스 JDK 프록시라
     * Mockito spy의 {@code callRealMethod()}가 "Cannot call abstract real method"로 죽는다.
     * 실제로 한 번 그렇게 돌려서 보드 tool이 전부 실패하고 MAP 시나리오가 GMS에 닿지도 못했다.
     * 보드 접근 여부는 Spring AI tool 로그({@code getCurrentPlan})로 충분히 보인다.
     */
    @Autowired
    BlockRepository blockRepository;

    // spy는 관찰 전용이다 — 모든 호출이 callRealMethod()로 실제 외부 API를 그대로 탄다
    @MockitoSpyBean
    PlaceQueryService placeQueryService;
    @MockitoSpyBean
    FestivalQueryService festivalQueryService;
    @MockitoSpyBean
    TrainScheduleProvider trainScheduleProvider;
    @MockitoSpyBean
    BusScheduleProvider busScheduleProvider;
    @MockitoSpyBean
    FlightScheduleProvider flightScheduleProvider;

    private final List<String> toolTrace = Collections.synchronizedList(new ArrayList<>());
    private final List<RawSearch> rawSearches = Collections.synchronizedList(new ArrayList<>());
    /** Spring AI가 tool을 실행할 때마다 남기는 DEBUG 로그를 그대로 받는다 — tool 이름의 1차 근거 */
    private final ListAppender<ILoggingEvent> toolCallLog = new ListAppender<>();
    /** interceptor가 응답에서 web_search 블록을 봤을 때만 남기는 로그 — 실제 발화 여부의 유일한 근거 */
    private final ListAppender<ILoggingEvent> webSearchLog = new ListAppender<>();

    /** 카카오가 실제로 돌려준 원본 — "15건 → 재정렬 → 8건"의 앞자리를 눈으로 확인하려고 남긴다 */
    private record RawSearch(String keyword, int rawCount, List<String> rawOrder) {
    }

    private record Anchor(String name, double lat, double lng) {
    }

    /**
     * 한 시나리오 = 모드 + 질의 (+ 사용자가 보고 있는 Day). 모드를 순번에서 유도하지 않고 각 건이
     * 직접 들고 있다 — 프로브처럼 모드를 손으로 고르는 세트는 "8번부터 MAP" 같은 순번 규칙에 얹을 수 없다.
     */
    private record Probe(ChatbotMode mode, String message, Integer dayNo) {

        /** Day 를 안 보내는 프로브 — 대다수가 이쪽이고, 그때 서버는 [Viewing] 블록을 빼고 보낸다 */
        Probe(ChatbotMode mode, String message) {
            this(mode, message, null);
        }
    }

    private record ScenarioRun(
            int index, String mode, String message, Integer dayNo, String reply,
            List<ChatbotResDTO.Candidate> candidates, long durationMs,
            List<String> toolNames, List<String> tools, List<RawSearch> searches,
            List<String> webSearches, String stackTrace) {
    }

    @Test
    @DisplayName("부산 1박2일 데모 시나리오 10건을 실제 진입점으로 한 번씩 돌린다")
    void runsTenDemoScenariosThroughRealEntryPoint() throws Exception {
        runScenarios(
                Path.of("build", "reports", "chatbot-e2e-transcript.md"),
                List.of(
                        "부산 가볼 만한 곳 추천해줘",
                        "지금 일정 요약해줘",
                        "이번 여행 기간에 부산에서 하는 축제 있어?",
                        "서울에서 부산 가는 KTX 시간표 알려줘",
                        "숙소에서 해운대해수욕장까지 택시로 얼마나 나와?",
                        "오늘 부산 날씨 어때?",
                        "이 근처 맛집 중에 회 잘하는 데 알려줘",
                        "이 근처 카페 추천해줘",
                        "조용한 카페 있을까?",
                        "애들 데려갈 만한 곳 있어?"));
    }

    /**
     * <b>과적합 점검</b>. 위 10건은 랭킹·프롬프트 작업을 <b>보면서 고친</b> 바로 그 세트다.
     * 그 세트가 좋아진 것은 기계가 실제로 좋아진 것과 구분되지 않는다 — 그 질의들에 맞춰
     * 손댄 결과일 수 있기 때문이다.
     *
     * <p>이 세트는 그 반복 과정에서 <b>한 번도 보지 않은</b> 질의다. 하네스·시드·모드 규칙은
     * 위와 완전히 같으므로, 차이는 오직 질의뿐이다. 여기서 tool 호출률·위치 재질문 없음·
     * 없는 수치 없음·카드 0건 아님이 그대로 유지되는지가 곧 "기계가 좋아졌는가"의 답이다.
     */
    @Test
    @DisplayName("처음 보는 질의 10건을 같은 하네스로 돌린다(과적합 점검)")
    void runsFreshScenarioSetThroughRealEntryPoint() throws Exception {
        runScenarios(
                Path.of("build", "reports", "chatbot-e2e-transcript-fresh.md"),
                List.of(
                        "부산에서 아이랑 갈 만한 실내 관광지 알려줘",
                        "부산 광안리 쪽 야경 스팟 알려줘",
                        "다음 달에 부산에서 열리는 축제 뭐 있어?",
                        "부산역에서 감천문화마을까지 대중교통으로 어떻게 가?",
                        "해운대에서 자갈치시장까지 자차로 가면 기름값 얼마나 나와?",
                        "지금 일정에서 둘째 날 뭐 하기로 했더라?",
                        "부산 국제시장 근처에 주차장 있어?",
                        "이 근처에 사진 찍기 좋은 데 있어?",
                        "여기 술집도 있어?",
                        "근처에 편의점 어디 있어?"));
    }

    /**
     * <b>web_search가 애초에 발화하는가</b>를 가른다. 앞선 30건에서 {@code 웹검색} 칸은 한 번도
     * 켜지지 않았고, {@code tool_choice}로 강제하지 않는 GENERAL에서도 그랬다. 남은 설명은 둘뿐이다.
     *
     * <ul>
     *   <li>(a) SSAFY GMS 게이트웨이가 주입된 서버tool을 조용히 떨어뜨려 <b>모델이 그 tool을 아예 못 본다</b>.</li>
     *   <li>(b) 모델은 보고 있었지만 <b>웹이 필요한 질의가 한 건도 없었다</b> — 장소는 카카오가,
     *       일정은 보드가, 교통은 ODsay가 이미 덮었다.</li>
     * </ul>
     *
     * <p>그래서 이 세 건은 <b>웹이 아니면 풀 수 없는 것</b>만 묻는다: 지금의 영업 여부, 공식 홈페이지
     * URL, 실제 후기. 카카오·보드·ODsay 어느 쪽도 답을 갖고 있지 않다.
     *
     * <p><b>읽는 법</b> — 세 건 모두 {@code (호출 안 함)}이면 (a)다: 서버tool이 모델에 닿지 않고 있으니
     * 게이트웨이 쪽을 봐야 한다. 한 건이라도 사용이 찍히면 (b)다: web_search는 정상 동작하고
     * 앞선 라운드는 그저 웹이 필요 없었을 뿐이므로 프롬프트를 고칠 일이 아니다.
     *
     * <p>측정만 한다 — 프롬프트·interceptor·운영 코드는 건드리지 않는다.
     */
    @Test
    @DisplayName("웹이 아니면 못 푸는 질의 3건으로 web_search 발화 여부를 가른다")
    void probesWhetherWebSearchFiresAtAll() throws Exception {
        runProbes(
                Path.of("build", "reports", "chatbot-e2e-transcript-websearch-probe.md"),
                List.of(
                        new Probe(ChatbotMode.GENERAL, "스타벅스 해운대동백점 지금도 영업해?"),
                        new Probe(ChatbotMode.GENERAL, "감천문화마을 공식 홈페이지 링크 알려줘"),
                        new Probe(ChatbotMode.MAP, "조용한 카페 찾아줘, 실제 후기도 알려줘")));
    }

    /**
     * 팀 시연 대본({@code exec/04-시연-시나리오.md} §9-2)이 일반 모드 예시로 적어 둔 <b>그 문장</b>을
     * 그대로 던진다. 다른 세트의 "부산 가볼 만한 곳 추천해줘"와는 모양이 다르다 — 이쪽은 "코스"라
     * Day별 서술형 답변으로 흐르기 쉽고, 그러면 카드가 붙지 않는다.
     *
     * <p>시연에서 챗봇이 처음 등장하는 장면이 바로 이 질문이고, 거기서 카드가 0건이면
     * "추천이 텍스트가 아니라 곧바로 담을 수 있는 카드"라는 이 서비스의 핵심 포인트가 그 자리에서
     * 죽는다. 그래서 대본의 문장을 추측으로 바꿔 쓰지 않고 원문 그대로 확인한다.
     */
    @Test
    @DisplayName("시연 대본 9-2의 예시 질의가 카드를 만드는지 확인한다")
    void probesDemoScriptCourseQuery() throws Exception {
        runProbes(
                Path.of("build", "reports", "chatbot-e2e-transcript-demo-script.md"),
                List.of(
                        // 가장 애매한 표현을 <b>맨 앞</b>에 둔다. 프로브는 한 대화 안에서 순차
                        // 실행되므로, 뒤에 두면 앞 턴이 점심 장소를 이미 말해 준 덕에 통과할 수 있다.
                        // 그러면 "새 대화의 첫 질문"이라는 시연 조건을 검증하지 못한다.
                        // Day 1 을 보고 있다고 함께 보낸다 — 보드에 점심격 블록이 둘(Day 1 해운대암소갈비집
                        // 12:00 / Day 2 원조할매국밥 09:00)이라, 이게 없으면 "어느 날이신가요?"로 되물어 0건이 됐다
                        // Day 를 <b>2</b>로 보낸다. Day 1 을 보내면 폴백("가장 이른 날")과 결과가 같아
                        // [Viewing] 을 실제로 썼는지 가릴 수 없다. Day 2 의 식사 블록은 원조할매국밥이므로,
                        // 답이 해운대암소갈비집이면 폴백을 탄 것이고 원조할매국밥이면 [Viewing] 을 쓴 것이다
                        new Probe(ChatbotMode.GENERAL, "점심 먹은 데 근처에 카페 있어?", 2),
                        // 실제 시연에서 쓸 문장. 보드의 점심 블록(해운대암소갈비집 12:00)을 앵커로
                        // 잡아야 하며, 이 경로(nearPlaceName)는 다른 세트가 한 번도 건드리지 않았다
                        new Probe(ChatbotMode.GENERAL, "우리 점심 먹고 나서 근처 갈만한 카페 추천해줘", 1),
                        // 대본이 예시로 적어 둔 문장. 카드가 0건임을 이미 확인했고 기준선으로만 남긴다
                        new Probe(ChatbotMode.GENERAL, "부산 2박 3일 코스 추천해줘")));
    }

    /**
     * 순번으로 모드를 정하던 기존 두 세트를 위한 입구. 규칙은 그대로 <b>8번째부터 MAP</b>이며,
     * 각 건에 그 모드를 박아 {@link #runProbes}로 넘긴다 — 하네스는 하나뿐이다.
     */
    private void runScenarios(Path transcript, List<String> messages) throws Exception {
        runProbes(transcript, IntStream.rangeClosed(1, messages.size())
                .mapToObj(index -> new Probe(
                        index >= 8 ? ChatbotMode.MAP : ChatbotMode.GENERAL, messages.get(index - 1)))
                .toList());
    }

    private void runProbes(Path transcript, List<Probe> probes) throws Exception {
        Assumptions.assumeTrue(notBlank(dotenv("GMS_API_KEY")), "GMS_API_KEY 없음 — .env 확인 필요");
        Assumptions.assumeTrue(notBlank(dotenv("KAKAO_REST_API_KEY")), "KAKAO_REST_API_KEY 없음 — .env 확인 필요");

        User me = seedUser();
        Project project = seedBusanTrip(me);
        List<Anchor> anchors = seedBusanBoard(project, me);
        long festivalCount = awaitFestivalSync();

        installToolCallLogCapture();
        installWebSearchLogCapture();
        installObservers();

        // 실호출은 크레딧과 쿼터를 쓴다 — 렌더링 버그 하나로 실행 전체가 날아가지 않도록
        // 시나리오가 끝날 때마다 파일에 즉시 흘려 쓴다(실제로 한 번 날려 먹었다).
        StringBuilder report = new StringBuilder(header(project, anchors, festivalCount));
        flush(transcript, report);

        List<ScenarioRun> runs = new ArrayList<>();
        for (int index = 1; index <= probes.size(); index++) {
            Probe probe = probes.get(index - 1);
            ScenarioRun run = send(index, project, me, probe);
            runs.add(run);
            report.append(renderSafely(run, anchors));
            flush(transcript, report);
        }
        report.append(renderSummarySafely(runs));
        flush(transcript, report);

        System.out.println(report);
        System.out.println("전체 기록 파일: " + transcript.toAbsolutePath());
        verifyMinimally(runs);
    }

    // ----- 시드: 데모와 같은 부산 1박2일 보드 -----

    private Project seedBusanTrip(User me) {
        TravelGroup group = travelGroupRepository.save(TravelGroup.builder()
                .name("부산 원정대")
                .inviteCode(UUID.randomUUID().toString().substring(0, 8))
                .inviteExpiresAt(LocalDateTime.now().plusDays(7))
                .build());
        groupMemberRepository.save(GroupMember.builder().travelGroup(group).user(me).build());
        groupMemberRepository.save(GroupMember.builder().travelGroup(group).user(seedUser()).build());
        return projectRepository.save(Project.builder()
                .name("부산 1박2일")
                .travelGroup(group)
                .destination("부산")
                .startDate(TRIP_START)
                .endDate(TRIP_END)
                .budgetHeadcount(2)
                .targetBudget(600_000)
                .transportPrefs(List.of(TransportPref.PUBLIC))
                .keywords(List.of("바다", "맛집", "야경"))
                .build());
    }

    /**
     * Day 1~2 체인 + 후보 1건. 좌표는 실제 부산 좌표라 거리 신호와 "도보 N분" 문구가
     * 실제로 검증 가능한 값이 된다 — 지금까지의 live 테스트는 보드가 비어 있어
     * 거리 기준이 항상 뷰포트 중심이었고 카테고리 페널티는 한 번도 발화하지 않았다.
     */
    private List<Anchor> seedBusanBoard(Project project, User author) {
        List<Anchor> anchors = new ArrayList<>();
        anchors.add(seedBlock(project, author, BlockCategory.FOOD, "해운대암소갈비집", "a0",
                720, 90, 92_000, 35.16332, 129.16523));
        anchors.add(seedBlock(project, author, BlockCategory.SPOT, "해운대해수욕장", "a1",
                840, 120, 0, 35.15872, 129.16045));
        anchors.add(seedBlock(project, author, BlockCategory.SPOT, "동백섬 누리마루", "a2",
                990, 90, 0, 35.15325, 129.15251));
        anchors.add(seedBlock(project, author, BlockCategory.STAY, "그랜드 조선 부산", "a3",
                1140, 660, 260_000, 35.15733, 129.15476));
        anchors.add(seedBlock(project, author, BlockCategory.FOOD, "원조할매국밥", "a4",
                1440 + 540, 60, 24_000, 35.16218, 129.16113));
        anchors.add(seedBlock(project, author, BlockCategory.SPOT, "광안리해수욕장", "a5",
                1440 + 660, 120, 0, 35.15322, 129.11862));
        anchors.add(seedBlock(project, author, BlockCategory.SPOT, "부산엑스더스카이", "a6",
                1440 + 840, 90, 54_000, 35.15966, 129.15290));
        // 후보(POOL) — 아직 어느 Day에도 놓이지 않은 블록
        anchors.add(seedBlock(project, author, BlockCategory.SPOT, "감천문화마을", "a7",
                null, 120, 0, 35.09750, 129.01070));
        return List.copyOf(anchors);
    }

    private Anchor seedBlock(Project project, User author, BlockCategory category, String name,
                             String orderKey, Integer startOffsetMinutes, int durationMin, int budget,
                             double lat, double lng) {
        blockRepository.save(Block.builder()
                .name(name)
                .category(category)
                .orderKey(orderKey)
                .startOffsetMinutes(startOffsetMinutes)
                .durationMin(durationMin)
                .budget(budget)
                .lat(BigDecimal.valueOf(lat))
                .lng(BigDecimal.valueOf(lng))
                .source(BlockSource.KAKAO)
                .project(project)
                .author(author)
                .build());
        return new Anchor(name, lat, lng);
    }

    /**
     * 기동 시 비동기로 도는 TourAPI 전량 수집을 기다린다. 건수가 15초간 늘지 않으면 끝난 것으로 본다 —
     * 배치가 완료 신호를 남기지 않으므로 관찰 가능한 값은 적재 건수뿐이다.
     */
    private long awaitFestivalSync() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 300_000;
        long previous = -1;
        long stableSince = 0;
        while (System.currentTimeMillis() < deadline) {
            long count = festivalRepository.count();
            if (count > 0 && count == previous) {
                if (stableSince == 0) {
                    stableSince = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - stableSince >= 15_000) {
                    return count;
                }
            } else {
                stableSince = 0;
            }
            previous = count;
            Thread.sleep(3_000);
        }
        return festivalRepository.count();
    }

    // ----- 관찰: 실동작은 그대로 두고 호출만 기록한다 -----

    /**
     * Spring AI는 tool을 실행할 때마다
     * {@code DefaultToolCallingManager}에서 DEBUG로 {@code "Executing tool call: {이름}"}을 남긴다.
     * 그 로거만 DEBUG로 올려 받아 두면 tool 호출 여부를 <b>추측 없이</b> 알 수 있다 —
     * spy는 tool 아래 협력자를 보는 것이라 tool 자체가 불렸는지는 간접 신호일 뿐이다.
     */
    private void installToolCallLogCapture() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger("org.springframework.ai.model.tool.DefaultToolCallingManager");
        logger.setLevel(Level.DEBUG);
        toolCallLog.start();
        logger.addAppender(toolCallLog);
    }

    private List<String> executedToolNames() {
        synchronized (toolCallLog) {
            return toolCallLog.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("Executing tool call: "))
                    .map(message -> message.substring("Executing tool call: ".length()))
                    .toList();
        }
    }

    /**
     * 서버tool web_search가 실제로 불렸는지는 tool 호출 로그에 안 나온다 — 모델 쪽에서 도는
     * 서버tool이라 Spring AI의 tool 루프를 타지 않기 때문이다. {@code WebSearchInterceptor}가
     * 응답에서 web_search 블록을 봤을 때만 남기는 로그를 tool 로그와 <b>같은 방식</b>으로 받는다.
     * 이게 있어야 "웹에서 확인했다는 문장이 0건"이 프롬프트 문제인지 발화 자체가 없는 것인지 갈린다.
     */
    private void installWebSearchLogCapture() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(WebSearchInterceptor.class);
        logger.setLevel(Level.INFO);
        webSearchLog.start();
        logger.addAppender(webSearchLog);
    }

    /**
     * 접두사를 문자열로 복제하지 않고 {@code WebSearchInterceptor}의 상수를 그대로 참조한다 —
     * 복제하면 로그 문구만 바뀌었을 때 이 절이 조용히 비고, "web_search가 안 불린다"는
     * 잘못된 결론이 나온다. 상수 참조라 문구가 바뀌면 자동으로 따라간다.
     */
    private List<String> webSearchUses() {
        synchronized (webSearchLog) {
            return webSearchLog.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith(WebSearchInterceptor.WEB_SEARCH_LOG_PREFIX))
                    .toList();
        }
    }

    private void installObservers() {
        doAnswer(invocation -> {
            List<PlaceResDTO.Place> found = passThrough(invocation.callRealMethod());
            String keyword = invocation.getArgument(0);
            rawSearches.add(new RawSearch(keyword, found.size(), names(found)));
            toolTrace.add("searchPlacesInView(keyword=%s) → 카카오 원본 %d건".formatted(keyword, found.size()));
            return found;
        }).when(placeQueryService).searchPlacesInRect(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble());

        doAnswer(invocation -> {
            List<PlaceResDTO.Place> found = passThrough(invocation.callRealMethod());
            String query = invocation.getArgument(0);
            rawSearches.add(new RawSearch(query, found.size(), names(found)));
            toolTrace.add("searchPlaces(query=%s) → 카카오 원본 %d건".formatted(query, found.size()));
            return found;
        }).when(placeQueryService).searchPlaces(anyString(), any(), any());

        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            toolTrace.add("getTaxiRoute(카카오 모빌리티) → " + result);
            return result;
        }).when(placeQueryService).getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());

        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            toolTrace.add("getWalkingRoute(카카오 모빌리티) → " + result);
            return result;
        }).when(placeQueryService).getWalkingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());

        doAnswer(invocation -> {
            List<?> found = (List<?>) invocation.callRealMethod();
            toolTrace.add("findFestivalsForCurrentTrip(region=%s) → %d건"
                    .formatted(invocation.getArgument(0), found.size()));
            return found;
        }).when(festivalQueryService).findByRegionAndDateRange(anyString(), any(), any());

        doAnswer(invocation -> traceSchedule("getTrainSchedule", invocation.getArgument(0),
                invocation.getArgument(1), invocation.callRealMethod()))
                .when(trainScheduleProvider).findSchedule(anyString(), anyString());
        doAnswer(invocation -> traceSchedule("getBusSchedule", invocation.getArgument(0),
                invocation.getArgument(1), invocation.callRealMethod()))
                .when(busScheduleProvider).findSchedule(anyString(), anyString());
        doAnswer(invocation -> traceSchedule("getFlightSchedule", invocation.getArgument(0),
                invocation.getArgument(1), invocation.callRealMethod()))
                .when(flightScheduleProvider).findSchedule(anyString(), anyString());
    }

    private Object traceSchedule(String tool, String from, String to, Object result) {
        toolTrace.add("%s(%s → %s) → %d건".formatted(tool, from, to, ((List<?>) result).size()));
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<PlaceResDTO.Place> passThrough(Object realResult) {
        return (List<PlaceResDTO.Place>) realResult;
    }

    private List<String> names(List<PlaceResDTO.Place> places) {
        return places.stream().map(PlaceResDTO.Place::name).toList();
    }

    // ----- 실행 -----

    private ScenarioRun send(int index, Project project, User me, Probe probe) {
        toolTrace.clear();
        rawSearches.clear();
        toolCallLog.list.clear();
        webSearchLog.list.clear();
        ChatbotMode mode = probe.mode();
        String message = probe.message();
        ChatbotReqDTO.SendMessage request = new ChatbotReqDTO.SendMessage(
                message, mode, mode == ChatbotMode.MAP ? HAEUNDAE_VIEWPORT : null, probe.dayNo());

        long startedAt = System.nanoTime();
        try {
            ChatbotResDTO.MessageResult result =
                    chatbotCommandService.sendMessage(project.getId(), me.getId(), request);
            long elapsed = (System.nanoTime() - startedAt) / 1_000_000;
            return new ScenarioRun(index, mode.name(), message, probe.dayNo(), result.reply(), result.candidates(),
                    elapsed, executedToolNames(), List.copyOf(toolTrace), List.copyOf(rawSearches),
                    webSearchUses(), null);
        } catch (RuntimeException e) {
            long elapsed = (System.nanoTime() - startedAt) / 1_000_000;
            // 한 시나리오의 실패가 나머지를 못 돌게 하면 안 된다 — 기록하고 계속 간다
            return new ScenarioRun(index, mode.name(), message, probe.dayNo(), null, List.of(),
                    elapsed, executedToolNames(), List.copyOf(toolTrace), List.copyOf(rawSearches),
                    webSearchUses(), stackTraceOf(e));
        }
    }

    // ----- 단언: 다툼의 여지가 없는 것만 -----

    private void verifyMinimally(List<ScenarioRun> runs) {
        SoftAssertions softly = new SoftAssertions();
        for (ScenarioRun run : runs) {
            softly.assertThat(run.stackTrace())
                    .as("시나리오 %d(%s)가 예외로 끝났다", run.index(), run.message())
                    .isNull();
            if (run.stackTrace() != null) {
                continue;
            }
            softly.assertThat(run.reply())
                    .as("시나리오 %d 답변이 비었다", run.index())
                    .isNotBlank();
            for (ChatbotResDTO.Candidate candidate : run.candidates()) {
                softly.assertThat(candidate.lat())
                        .as("시나리오 %d 카드 '%s' 위도", run.index(), candidate.name()).isNotNull();
                softly.assertThat(candidate.lng())
                        .as("시나리오 %d 카드 '%s' 경도", run.index(), candidate.name()).isNotNull();
                if (!"MAP".equals(run.mode()) || candidate.lat() == null || candidate.lng() == null) {
                    continue;
                }
                softly.assertThat(candidate.lat())
                        .as("시나리오 %d 카드 '%s'가 뷰포트 위도 범위 밖", run.index(), candidate.name())
                        .isBetween(HAEUNDAE_VIEWPORT.swLat(), HAEUNDAE_VIEWPORT.neLat());
                softly.assertThat(candidate.lng())
                        .as("시나리오 %d 카드 '%s'가 뷰포트 경도 범위 밖", run.index(), candidate.name())
                        .isBetween(HAEUNDAE_VIEWPORT.swLng(), HAEUNDAE_VIEWPORT.neLng());
            }
        }
        softly.assertAll();
    }

    // ----- 리포트 -----

    private String header(Project project, List<Anchor> anchors, long festivalCount) {
        StringBuilder out = new StringBuilder();
        out.append("# 챗봇 E2E 라이브 실행 기록\n\n");
        out.append("- 실행 시각: ").append(LocalDateTime.now()).append('\n');
        out.append("- 프로젝트: ").append(project.getName())
                .append(" / 목적지 ").append(project.getDestination())
                .append(" / ").append(TRIP_START).append(" ~ ").append(TRIP_END)
                .append(" / ").append(project.getBudgetHeadcount()).append("인")
                .append(" / 목표예산 ").append(project.getTargetBudget()).append("원\n");
        out.append("- 시드 블록: ").append(anchors.size()).append("건\n");
        out.append("- MAP 뷰포트: sw(").append(HAEUNDAE_VIEWPORT.swLat()).append(", ")
                .append(HAEUNDAE_VIEWPORT.swLng()).append(") ne(").append(HAEUNDAE_VIEWPORT.neLat())
                .append(", ").append(HAEUNDAE_VIEWPORT.neLng()).append(")\n");
        out.append("- 적재된 축제(TourAPI 실수집): ").append(festivalCount).append("건\n");
        out.append("- 시드 블록 좌표: ")
                .append(anchors.stream().map(a -> "%s(%.5f, %.5f)".formatted(a.name(), a.lat(), a.lng()))
                        .reduce((l, r) -> l + " / " + r).orElse("-"))
                .append("\n\n");
        return out.toString();
    }

    /** 렌더링 실패가 실행 기록을 통째로 삼키지 않게 한다 — 원자료라도 남기는 편이 낫다 */
    private String renderSafely(ScenarioRun run, List<Anchor> anchors) {
        try {
            return renderScenario(run, anchors);
        } catch (RuntimeException e) {
            return "\n## 시나리오 %d 렌더링 실패 — 원자료\n```\n%s\n```\n```\n%s\n```\n"
                    .formatted(run.index(), run, stackTraceOf(e));
        }
    }

    private String renderSummarySafely(List<ScenarioRun> runs) {
        try {
            return renderSummary(runs);
        } catch (RuntimeException e) {
            return "\n## 요약 렌더링 실패\n```\n" + stackTraceOf(e) + "\n```\n";
        }
    }

    private String renderScenario(ScenarioRun run, List<Anchor> anchors) {
        StringBuilder out = new StringBuilder();
        out.append("\n").append("=".repeat(78)).append('\n');
        out.append("## 시나리오 %d [%s] %s\n".formatted(run.index(), run.mode(), run.message()));
        out.append("=".repeat(78)).append('\n');
        out.append("소요: ").append(run.durationMs()).append("ms / 카드 ")
                .append(run.candidates().size()).append("건\n");
        // 서버가 [Viewing] 블록으로 실어 보낸 값 — 다중 후보에서 어느 Day 를 골랐는지 읽을 근거
        out.append("보고 있는 Day: ").append(run.dayNo() == null ? "(안 보냄)" : run.dayNo()).append("\n\n");

        if (run.stackTrace() != null) {
            out.append("### 예외로 종료\n```\n").append(run.stackTrace()).append("\n```\n");
            return out.toString();
        }

        out.append("### 답변 (원문)\n");
        out.append("```\n").append(run.reply()).append("\n```\n\n");

        out.append("### 후보 카드 (").append(run.candidates().size()).append("건)\n");
        if (run.candidates().isEmpty()) {
            out.append("(없음)\n");
        } else {
            int order = 1;
            for (ChatbotResDTO.Candidate candidate : run.candidates()) {
                out.append("%d. %s | category=%s | lat=%s | lng=%s | placeId=%s | source=%s | sub=%s%s\n"
                        .formatted(order++, candidate.name(), candidate.category(), candidate.lat(),
                                candidate.lng(), candidate.placeId(), candidate.source(),
                                candidate.subCategory(), franchiseMark(candidate.name())));
                out.append("   ").append(nearestAnchorLine(candidate, anchors)).append('\n');
            }
        }

        out.append("\n### 실행된 tool (Spring AI 로그 기준)\n");
        out.append(run.toolNames().isEmpty()
                ? "(로컬 tool 호출 없음 — 자체 지식이나 서버측 web_search만 썼다는 뜻이다)\n"
                : run.toolNames().stream().map(n -> "- " + n + "\n").reduce("", String::concat));

        out.append("\n### web_search 사용\n");
        out.append(run.webSearches().isEmpty()
                ? "(호출 안 함)\n"
                : run.webSearches().stream().map(w -> "- " + w + "\n").reduce("", String::concat));

        out.append("\n### tool 아래 실제 외부 호출\n");
        if (run.tools().isEmpty()) {
            out.append("(없음)\n");
        } else {
            run.tools().forEach(t -> out.append("- ").append(t).append('\n'));
        }

        if (!run.searches().isEmpty()) {
            out.append("\n### 카카오 원본 결과 (재정렬 전)\n");
            for (RawSearch search : run.searches()) {
                out.append("- \"%s\" → %d건: %s\n"
                        .formatted(search.keyword(), search.rawCount(), String.join(", ", search.rawOrder())));
            }
        }

        List<String> forbidden = forbiddenHits(run.reply());
        out.append("\n### 없는 수치 스캔 (단정 여부는 사람이 답변을 읽고 판단)\n");
        out.append(forbidden.isEmpty() ? "(없음)\n" : String.join("\n", forbidden) + "\n");

        List<String> reasons = reasonHits(run.reply());
        out.append("\n### 추천 이유 표현\n");
        out.append(reasons.isEmpty() ? "(없음)\n" : String.join("\n", reasons) + "\n");
        return out.toString();
    }

    private String renderSummary(List<ScenarioRun> runs) {
        StringBuilder out = new StringBuilder("\n" + "=".repeat(78) + "\n## 요약\n" + "=".repeat(78) + "\n");
        long failed = runs.stream().filter(r -> r.stackTrace() != null).count();
        out.append("완료 ").append(runs.size() - failed).append("건 / 예외 ").append(failed).append("건\n\n");
        out.append("| # | mode | ms | 카드 | 웹검색 | 수치스캔 | 메시지 |\n|---|---|---|---|---|---|---|\n");
        for (ScenarioRun run : runs) {
            out.append("| %d | %s | %d | %d | %s | %d | %s |\n".formatted(run.index(), run.mode(),
                    run.durationMs(), run.candidates().size(), run.webSearches().isEmpty() ? "-" : "O",
                    forbiddenHits(run.reply()).size(), run.message()));
        }
        runs.stream().max(java.util.Comparator.comparingLong(ScenarioRun::durationMs)).ifPresent(slowest ->
                out.append("\n최장 시나리오: #%d (%dms) — %s\n"
                        .formatted(slowest.index(), slowest.durationMs(), slowest.message())));
        return out.toString();
    }

    /** MAP 시나리오의 "도보 N분"이 실제 좌표로 말이 되는지 사람이 판단할 근거를 남긴다 */
    private String nearestAnchorLine(ChatbotResDTO.Candidate candidate, List<Anchor> anchors) {
        if (candidate.lat() == null || candidate.lng() == null) {
            return "좌표 없음";
        }
        Anchor nearest = null;
        double best = Double.MAX_VALUE;
        for (Anchor anchor : anchors) {
            double meters = Haversine.distanceMeters(
                    anchor.lat(), anchor.lng(), candidate.lat(), candidate.lng());
            if (meters < best) {
                best = meters;
                nearest = anchor;
            }
        }
        return "가장 가까운 시드 블록: %s %.0fm (도보 환산 %d분)"
                .formatted(nearest == null ? "-" : nearest.name(), best,
                        Math.max(1L, Math.round(best / 67.0)));
    }

    private String franchiseMark(String name) {
        if (name == null) {
            return "";
        }
        return FRANCHISE_BRANDS.stream().anyMatch(name::contains) ? "  ← 전국 체인" : "";
    }

    /** 스캔 로직은 {@link FabricatedDataScanner}에 있다 — 단위 테스트로 고정하려고 뺐다 */
    private List<String> forbiddenHits(String reply) {
        return FabricatedDataScanner.scan(reply);
    }

    private List<String> reasonHits(String reply) {
        if (reply == null) {
            return List.of();
        }
        List<String> hits = new ArrayList<>();
        Matcher matcher = REASON_PATTERN.matcher(reply);
        while (matcher.find()) {
            int from = Math.max(0, matcher.start() - 30);
            int to = Math.min(reply.length(), matcher.end() + 30);
            hits.add("- \"%s\" … %s".formatted(matcher.group(),
                    reply.substring(from, to).replace('\n', ' ').trim()));
        }
        return hits;
    }

    private void flush(Path transcript, StringBuilder report) throws IOException {
        Files.createDirectories(transcript.getParent());
        Files.writeString(transcript, report.toString());
    }

    private String stackTraceOf(Throwable e) {
        StringWriter writer = new StringWriter();
        e.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** .env는 <b>읽기만</b> 한다. spring-dotenv가 이미 읽는 파일이라 테스트가 별도 값을 만들지 않는다. */
    private static String dotenv(String key) {
        Path env = Path.of(".env");
        if (!Files.exists(env)) {
            return null;
        }
        try {
            String prefix = key.toUpperCase(Locale.ROOT) + "=";
            return Files.readAllLines(env).stream()
                    .filter(line -> line.startsWith(prefix))
                    .map(line -> line.substring(prefix.length()).trim())
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
