package com.ssafy.ieumgil.domain.festival.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TourAPI {@code homepage} 필드에서 URL만 뽑는다.
 *
 * <p>이 필드는 {@code <a href="http://...">...</a>} HTML 앵커이거나, 평문 URL이거나,
 * 빈 문자열이다. 앵커면 href를, 평문이면 그대로, 그 외에는 null을 준다.
 */
public final class HomepageUrlExtractor {

    private static final Pattern HREF = Pattern.compile("href=[\"']([^\"']+)[\"']");
    private static final Pattern PLAIN_URL = Pattern.compile("^\\s*(https?://\\S+)\\s*$");

    public static String extract(String rawHomepage) {
        if (rawHomepage == null || rawHomepage.isBlank()) {
            return null;
        }
        Matcher href = HREF.matcher(rawHomepage);
        if (href.find()) {
            return href.group(1);
        }
        Matcher plain = PLAIN_URL.matcher(rawHomepage);
        if (plain.find()) {
            return plain.group(1);
        }
        return null;
    }

    private HomepageUrlExtractor() {
    }
}
