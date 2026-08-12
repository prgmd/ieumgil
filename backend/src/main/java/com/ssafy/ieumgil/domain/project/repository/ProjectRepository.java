package com.ssafy.ieumgil.domain.project.repository;

import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.exception.ProjectErrorCode;
import com.ssafy.ieumgil.global.exception.CustomException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /**
     * 여러 그룹의 프로젝트 개수를 한 번에 집계한다.
     * 그룹마다 count 쿼리를 던지면 N+1이 되므로 GROUP BY로 묶는다.
     * 완료 여부와 무관한 전체 개수이며, 소프트 삭제분은 제외한다.
     */
    @Query("""
            SELECT p.travelGroup.id AS groupId, COUNT(p) AS projectCount
            FROM Project p
            WHERE p.travelGroup.id IN :groupIds
              AND p.deletedAt IS NULL
            GROUP BY p.travelGroup.id
            """)
    List<GroupProjectCount> countByGroupIdIn(@Param("groupIds") List<Long> groupIds);

    /** 삭제되지 않은 프로젝트만 조회 */
    Optional<Project> findByIdAndDeletedAtIsNull(Long id);

    /** 살아 있는 프로젝트를 가져오거나 404(PROJECT_NOT_FOUND)를 던진다. 서비스·AOP에 흩어진 관용구를 한곳으로 모은다. */
    default Project findAliveByIdOrThrow(Long id) {
        return findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException(ProjectErrorCode.PROJECT_NOT_FOUND));
    }

    /** 그룹의 프로젝트 카드 목록. 최근 만든 것이 위로 온다 */
    List<Project> findByTravelGroupIdAndDeletedAtIsNullOrderByIdDesc(Long travelGroupId);

    /**
     * 인가용 그룹 id 조회 — 프로젝트와 그룹이 <b>둘 다</b> 살아 있어야 한다.
     *
     * <p>그룹 소프트 삭제는 프로젝트를 함께 지우지 않고 멤버십 행도 남긴다. 프로젝트만
     * 확인하면 삭제된 그룹의 대시보드를 계속 인가하게 되는데, REST(@GroupMember)는 이미
     * 그룹 생존을 확인하므로 두 경로의 기준이 어긋난다 — 그 틈이 곧 WS 우회로다.
     *
     * <p>FK 값만 필요하므로 엔티티를 싣지 않는다(기존 findByIdAndDeletedAtIsNull은 전 컬럼을 읽었다).
     */
    @Query("""
            SELECT p.travelGroup.id
            FROM Project p
            WHERE p.id = :projectId
              AND p.deletedAt IS NULL
              AND p.travelGroup.deletedAt IS NULL
            """)
    Optional<Long> findAliveGroupIdById(@Param("projectId") Long projectId);

    /** 위 집계 결과를 담는 그릇. 구현체는 Spring이 만들어준다. */
    interface GroupProjectCount {
        Long getGroupId();

        long getProjectCount();
    }
}
