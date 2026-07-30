package com.ssafy.ieumgil.domain.group.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 요청자가 해당 그룹의 멤버인지 검증한다. 비멤버면 403, 없는 그룹/프로젝트면 404.
 * GroupMemberAspect가 실제 검증을 수행하므로 서비스에는 검증 코드를 두지 않는다.
 *
 * <pre>
 * &#64;GroupMember                          // 파라미터에서 groupId를 찾는다
 * &#64;GroupMember(Source.PROJECT_ID)       // projectId로 그룹을 역추적한다
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GroupMember {

    /** 그룹을 어느 경로 변수로 찾을지 */
    Source value() default Source.GROUP_ID;

    enum Source {
        /** 파라미터 이름이 groupId */
        GROUP_ID,
        /** 파라미터 이름이 projectId — 프로젝트가 속한 그룹으로 검증한다 */
        PROJECT_ID,
        /** 파라미터 이름이 blockId — 블록→프로젝트→그룹 2홉 역추적. tombstone 블록도 통과시킨다(410은 서비스 판정) */
        BLOCK_ID
    }
}
