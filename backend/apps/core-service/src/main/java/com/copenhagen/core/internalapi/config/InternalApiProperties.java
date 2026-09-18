package com.copenhagen.core.internalapi.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 내부 API 설정(설계서 §10).
 *
 * <p>{@code app-link-base}만 기본값이 없다. App Link origin이 아직 확정되지 않아
 * (구현순서.md §4) 잘못된 값으로 조용히 인계 링크를 만드는 대신, 미설정이면 구매 인계 1건만
 * 503으로 막고 나머지 6개 엔드포인트는 정상 동작시킨다.
 */
@Component
public class InternalApiProperties {

    private final String appLinkBase;
    private final Duration handoffTtl;
    private final int defaultPageSize;

    public InternalApiProperties(
            @Value("${core.internal.app-link-base:}") String appLinkBase,
            @Value("${core.internal.handoff-ttl:PT30M}") Duration handoffTtl,
            @Value("${core.internal.default-page-size:10}") int defaultPageSize) {
        // mcp-server의 MCP_APP_LINK_BASE는 '/'로 끝나야만 기동한다(CommerceProperties). 양쪽 문자열이
        // 정확히 같아야 하므로 여기서도 같은 형태로 맞춘다.
        this.appLinkBase = appLinkBase == null || appLinkBase.isBlank()
                ? null
                : appLinkBase.endsWith("/") ? appLinkBase : appLinkBase + "/";
        this.handoffTtl = handoffTtl;
        this.defaultPageSize = defaultPageSize;
    }

    public boolean hasAppLinkBase() {
        return appLinkBase != null;
    }

    public String appLinkBase() {
        return appLinkBase;
    }

    public Duration handoffTtl() {
        return handoffTtl;
    }

    public int defaultPageSize() {
        return defaultPageSize;
    }
}
