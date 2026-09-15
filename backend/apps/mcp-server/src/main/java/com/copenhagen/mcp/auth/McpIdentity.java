package com.copenhagen.mcp.auth;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import java.time.Instant;
import java.util.Set;

/** 인증 필터가 생성해 SDK transport context로 전달하는 요청별 사용자 문맥. */
public record McpIdentity(String publicUserRef, String connectionRef, Set<String> scopes, Instant expiresAt) {

    public static final String CONTEXT_KEY = McpIdentity.class.getName();

    public McpIdentity {
        if (publicUserRef == null || publicUserRef.isBlank() || connectionRef == null || connectionRef.isBlank()
                || expiresAt == null) {
            throw new McpAuthException(McpAuthError.INVALID_TOKEN);
        }
        scopes = Set.copyOf(scopes);
    }

    public static McpIdentity from(McpSyncServerExchange exchange) {
        if (exchange == null || !(exchange.transportContext().get(CONTEXT_KEY) instanceof McpIdentity identity)) {
            throw new McpAuthException(McpAuthError.AUTHENTICATION_REQUIRED);
        }
        return identity;
    }

    public void requireScopes(Set<String> required) {
        if (!scopes.containsAll(required)) {
            throw new McpAuthException(McpAuthError.INSUFFICIENT_SCOPE, required);
        }
    }

    /** ownerPublicUserRef는 Tool 입력이 아니라 Core에서 조회·검증한 자원 소유자여야 한다. */
    public void requireOwner(String ownerPublicUserRef) {
        if (!publicUserRef.equals(ownerPublicUserRef)) {
            throw new McpAuthException(McpAuthError.ACCESS_DENIED);
        }
    }

    @Override
    public String toString() {
        return "McpIdentity[redacted]";
    }
}
