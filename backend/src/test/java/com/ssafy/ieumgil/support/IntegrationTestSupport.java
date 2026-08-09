package com.ssafy.ieumgil.support;

import com.ssafy.ieumgil.domain.group.entity.GroupMember;
import com.ssafy.ieumgil.domain.group.entity.TravelGroup;
import com.ssafy.ieumgil.domain.group.repository.GroupMemberRepository;
import com.ssafy.ieumgil.domain.group.repository.TravelGroupRepository;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.domain.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 통합 테스트 공통 베이스 — Testcontainers로 실제 PostgreSQL/Redis를 띄운다.
 *
 * H2가 아니라 실제 PostgreSQL인 이유: 행 잠금(SELECT ... FOR UPDATE)과 JSONB 컬럼은
 * 방언 차이로 H2에서 재현되지 않는다. 도커 이미지 버전은 docker-compose와 같은 계열
 * (postgres:16-alpine / redis:7-alpine)로 맞춘다.
 *
 * 컨테이너는 싱글턴(static) — 테스트 클래스마다 새로 띄우면 분 단위로 느려지므로
 * JVM당 한 쌍을 전체 테스트가 공유한다. 데이터 격리는 컨테이너가 아니라
 * "각 테스트가 자기 그룹/프로젝트를 새로 만든다"로 확보한다.
 */
@SpringBootTest(properties = {
        // 배포 DB는 ddl-auto=none(수동 마이그레이션)이지만, 일회용 컨테이너는 엔티티로 스키마를 만든다
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // 유령 UPDATE 회귀 감시(OpPipelineIntegrationTest)가 쿼리 수를 세는 데 쓴다
        "spring.jpa.properties.hibernate.generate_statistics=true",
        // .env 없는 환경(CI/다른 팀원 PC)에서도 컨텍스트가 뜨도록 필수 값에 테스트 기본값을 준다
        "jwt.secret=test-only-jwt-secret-test-only-jwt-secret-0123456789",
        "oauth.kakao.client-id=test-client-id",
        "oauth.kakao.redirect-uri=http://localhost/oauth/test",
        // 오피넷 유가 provider는 기동 직후(ApplicationReadyEvent) 실제 API를 부른다. 테스트
        // 컨텍스트에서도 그 이벤트가 발생하므로, .env에 키가 있는 개발 PC에서는 테스트를 돌릴 때마다
        // 실호출이 나간다. 키를 비워 상수 유가 provider로 떨어뜨려 테스트를 네트워크에서 떼어 놓는다.
        "opinet.api-key=",
        // 같은 이유로 TourAPI 키도 비운다. 안 그러면 .env에 키가 있는 개발 PC에서 통합 테스트 컨텍스트가
        // 뜰 때마다 FestivalBatchService의 기동 동기화가 실 TourAPI 전량 수집을 백그라운드로 날린다.
        "tourapi.service-key=",
})
public abstract class IntegrationTestSupport {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
    }

    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected TravelGroupRepository travelGroupRepository;
    @Autowired
    protected GroupMemberRepository groupMemberRepository;
    @Autowired
    protected ProjectRepository projectRepository;

    // ----- 시드 헬퍼: 각 테스트가 자기만의 그룹/프로젝트를 만든다 (UNIQUE 충돌 방지용 랜덤 값) -----

    protected User seedUser() {
        return userRepository.save(User.builder()
                .kakaoId(ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE))
                .nickname("테스터")
                .build());
    }

    protected Project seedProject(User member) {
        TravelGroup group = travelGroupRepository.save(TravelGroup.builder()
                .name("테스트그룹")
                .inviteCode(UUID.randomUUID().toString().substring(0, 8))
                .inviteExpiresAt(java.time.LocalDateTime.now().plusDays(7))
                .build());
        groupMemberRepository.save(GroupMember.builder()
                .travelGroup(group)
                .user(member)
                .build());
        return projectRepository.save(Project.builder()
                .name("테스트여행")
                .travelGroup(group)
                .build());
    }
}
