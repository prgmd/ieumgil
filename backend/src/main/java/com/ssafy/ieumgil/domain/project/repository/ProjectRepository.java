package com.ssafy.ieumgil.domain.project.repository;

import com.ssafy.ieumgil.domain.project.entity.Project;
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

    /** 그룹의 프로젝트 카드 목록. 최근 만든 것이 위로 온다 */
    List<Project> findByTravelGroupIdAndDeletedAtIsNullOrderByIdDesc(Long travelGroupId);

    /** 위 집계 결과를 담는 그릇. 구현체는 Spring이 만들어준다. */
    interface GroupProjectCount {
        Long getGroupId();

        long getProjectCount();
    }
}
