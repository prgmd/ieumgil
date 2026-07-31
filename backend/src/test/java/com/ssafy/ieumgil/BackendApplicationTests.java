package com.ssafy.ieumgil;

import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

/** 컨텍스트 기동 검증 — Testcontainers가 DB/Redis를 제공하므로 로컬 DB 없이도 통과한다 */
class BackendApplicationTests extends IntegrationTestSupport {

	@Test
	void contextLoads() {
	}

}
