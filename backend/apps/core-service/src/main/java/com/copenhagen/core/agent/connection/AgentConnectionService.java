package com.copenhagen.core.agent.connection;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.agent.refreshtoken.AgentRefreshTokenService;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

/**
 * Agent 연동 upsert·목록·해제. 재인가 시 기존 연동 처리는 팀 사전 설계
 * ({@code erd&api수정작업용/erd 수정/agent_oauth_tables.md} "결정 사항 (추가) 1.")를 따라
 * "기존 활성 연동을 revoke하고 새 행을 INSERT"한다 — UPDATE로 처리하면 옛 scope를 실은
 * {@code agent_refresh_tokens}이 새 동의 범위보다 넓은 권한으로 계속 살아있을 수 있기 때문이다.
 */
@Service
public class AgentConnectionService {

    private static final TypeReference<List<String>> SCOPES_TYPE = new TypeReference<>() { };

    private final AgentConnectionRepository connectionRepository;
    private final AgentRefreshTokenService refreshTokenService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AgentConnectionService(
            AgentConnectionRepository connectionRepository,
            AgentRefreshTokenService refreshTokenService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.connectionRepository = connectionRepository;
        this.refreshTokenService = refreshTokenService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public AgentConnection replaceOnAuthorization(long userId, long oauthClientId, Set<String> grantedScopes) {
        connectionRepository.findActiveByUserIdAndOauthClientId(userId, oauthClientId)
                .ifPresent(existing -> {
                    connectionRepository.revoke(existing.id(), clock.instant());
                    refreshTokenService.revokeAllForConnection(existing.id());
                });

        UUID publicId = UUID.randomUUID();
        String grantedScopesJson = writeScopes(grantedScopes);
        long id = connectionRepository.insert(publicId, userId, oauthClientId, grantedScopesJson, clock.instant());
        return connectionRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Inserted agent connection not found: " + id));
    }

    public List<AgentConnectionSummary> list(long userId) {
        return connectionRepository.findActiveSummariesByUserId(userId);
    }

    @Transactional
    public void revoke(long userId, UUID connectionId) {
        AgentConnection connection = connectionRepository.findActiveByPublicIdAndUserId(connectionId, userId)
                .orElseThrow(() -> new CoreException(CoreErrorCode.AGENT_CONNECTION_NOT_FOUND));
        connectionRepository.revoke(connection.id(), clock.instant());
        refreshTokenService.revokeAllForConnection(connection.id());
    }

    public List<String> readScopes(String scopesJson) {
        return objectMapper.readValue(scopesJson, SCOPES_TYPE);
    }

    private String writeScopes(Set<String> scopes) {
        return objectMapper.writeValueAsString(scopes.stream().sorted().toList());
    }
}
