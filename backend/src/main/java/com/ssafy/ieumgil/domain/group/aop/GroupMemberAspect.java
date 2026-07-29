package com.ssafy.ieumgil.domain.group.aop;

import com.ssafy.ieumgil.domain.group.annotation.GroupMember;
import com.ssafy.ieumgil.domain.group.exception.GroupErrorCode;
import com.ssafy.ieumgil.domain.group.repository.GroupMemberRepository;
import com.ssafy.ieumgil.domain.group.repository.TravelGroupRepository;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import com.ssafy.ieumgil.global.apiPayload.code.GeneralErrorCode;
import com.ssafy.ieumgil.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * {@link GroupMember}가 붙은 컨트롤러 메서드에서 그룹 소속을 검증한다.
 *
 * 검증 순서는 서비스에 있던 것과 같다 — 존재 확인(404) 먼저, 멤버십(403) 나중.
 * 순서를 바꾸면 없는 그룹에 403이 나가 프론트가 권한 문제로 오해한다.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class GroupMemberAspect {

    private static final String GROUP_ID_PARAM = "groupId";
    private static final String PROJECT_ID_PARAM = "projectId";

    private final TravelGroupRepository travelGroupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final ProjectRepository projectRepository;

    @Before("@annotation(groupMember)")
    public void validateMembership(JoinPoint joinPoint, GroupMember groupMember) {
        Long userId = currentUserId();
        Long groupId = resolveGroupId(joinPoint, groupMember.value());

        if (!travelGroupRepository.existsByIdAndDeletedAtIsNull(groupId)) {
            throw new CustomException(GroupErrorCode.GROUP_NOT_FOUND);
        }
        if (!groupMemberRepository.existsMembership(groupId, userId)) {
            throw new CustomException(GroupErrorCode.NOT_GROUP_MEMBER);
        }
    }

    /** JwtAuthenticationFilter가 principal에 userId(Long)를 넣어둔다 */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new CustomException(GeneralErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private Long resolveGroupId(JoinPoint joinPoint, GroupMember.Source source) {
        if (source == GroupMember.Source.GROUP_ID) {
            return findLongArgument(joinPoint, GROUP_ID_PARAM);
        }

        Long projectId = findLongArgument(joinPoint, PROJECT_ID_PARAM);

        return projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new CustomException(
                        com.ssafy.ieumgil.domain.project.exception.ProjectErrorCode.PROJECT_NOT_FOUND))
                .getTravelGroup()
                .getId();
    }

    /**
     * 메서드 파라미터 중 이름이 일치하는 값을 꺼낸다.
     * 파라미터 이름은 컴파일 시 -parameters 옵션이 있어야 살아남는다(build.gradle에 지정).
     */
    private Long findLongArgument(JoinPoint joinPoint, String parameterName) {
        String[] names = ((MethodSignature) joinPoint.getSignature()).getParameterNames();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < names.length; i++) {
            if (parameterName.equals(names[i]) && args[i] instanceof Long value) {
                return value;
            }
        }

        throw new IllegalStateException(
                "@GroupMember를 붙인 메서드에 Long %s 파라미터가 없습니다: %s"
                        .formatted(parameterName, joinPoint.getSignature()));
    }
}
