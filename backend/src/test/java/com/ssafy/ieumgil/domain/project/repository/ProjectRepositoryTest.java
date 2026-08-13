package com.ssafy.ieumgil.domain.project.repository;

import com.ssafy.ieumgil.domain.project.exception.ProjectErrorCode;
import com.ssafy.ieumgil.global.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * {@link ProjectRepository#findAliveByIdOrThrow}의 not-found 분기 단위 테스트.
 *
 * <p>기존 테스트는 살아 있는 프로젝트가 있는 happy path만 지나가고, 소프트 삭제됐거나 없는
 * 프로젝트에서 404({@code PROJECT_NOT_FOUND})를 던지는 분기는 검증하지 않았다. 이 관용구는
 * 여러 레포가 공유하므로({@code TravelGroupRepository} 등) Project 하나를 대표로 검증한다.
 *
 * <p>레포 인터페이스의 default 메서드만 실행 대상이므로 Mockito 목에 {@code willCallRealMethod}로
 * default 본문만 실제로 돌리고, 그 안에서 부르는 {@code findByIdAndDeletedAtIsNull}은 스텁한다 —
 * Spring 컨텍스트·DB 없이 분기 하나만 좁게 본다.
 */
class ProjectRepositoryTest {

    @Test
    @DisplayName("findAliveByIdOrThrow: 살아 있는 프로젝트가 없으면 PROJECT_NOT_FOUND를 던진다")
    void findAliveByIdOrThrow_없으면_404를_던진다() {
        ProjectRepository repository = mock(ProjectRepository.class);
        given(repository.findByIdAndDeletedAtIsNull(42L)).willReturn(Optional.empty());
        given(repository.findAliveByIdOrThrow(42L)).willCallRealMethod();

        assertThatThrownBy(() -> repository.findAliveByIdOrThrow(42L))
                .isInstanceOf(CustomException.class)
                .extracting("code").isEqualTo(ProjectErrorCode.PROJECT_NOT_FOUND);
    }
}
