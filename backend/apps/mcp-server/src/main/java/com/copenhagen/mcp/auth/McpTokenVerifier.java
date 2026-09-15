package com.copenhagen.mcp.auth;

/**
 * 신뢰할 수 있는 제공자 계약으로 토큰과 현재 연결 상태를 검증하는 경계.
 * JWT는 서명 검증을, 불투명 토큰은 합의한 introspection을 완료한 뒤 결과를 반환한다.
 * 서명 없는 decode 결과나 클라이언트의 사용자 헤더를 검증 결과로 사용하지 않는다.
 * S15P21A207-315: 실제 Core 인증 계약 확정 후 운영 구현을 연결한다.
 */
@FunctionalInterface
public interface McpTokenVerifier {
    VerifiedMcpToken verify(String accessToken);
}
