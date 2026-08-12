package com.ssafy.ieumgil.global.realtime;

import java.util.LinkedHashMap;
import java.util.Map;

/** op 브로드캐스트 payload 조립 유틸 */
public final class OpPayloads {

    private OpPayloads() {
    }

    /** Map.of는 null 값을 거부하므로(예: startOffsetMinutes null = POOL 이동) 직접 담는다 */
    public static Map<String, Object> payloadWithNullable(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
