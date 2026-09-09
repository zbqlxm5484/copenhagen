package com.copenhagen.merchantmock.support;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 오류 응답 본문. Core 확정본 기준 세 필드다.
 *
 * <p>설계서 20장은 currentState·nextAction·retryable 을 더 요구하고 Pay 가 2026-09-09 에 여섯 필드
 * 절충안을 냈다. 셋이 확정하면 여기에 필드를 붙인다(DESIGN.md 11절, 19.1절).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, String requestId) {
}
