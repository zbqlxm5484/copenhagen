package com.copenhagen.core.agent.authorize;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.agent.client.FakeOauthClientRepository;
import com.copenhagen.core.agent.client.OauthClient;
import com.copenhagen.core.agent.connection.AgentConnection;
import com.copenhagen.core.agent.connection.AgentConnectionService;
import com.copenhagen.core.auth.login.LoginResult;
import com.copenhagen.core.auth.login.LoginService;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizeConsentServiceTest {

    private static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final String CLIENT_ID = "chatgpt-plugin";
    private static final String REDIRECT_URI = "https://chat.openai.com/callback";
    private static final String CODE_CHALLENGE = "challenge-value";

    private FakeOauthClientRepository clientRepository;
    private FakeOauthAuthorizationRequestRepository requestRepository;
    private AgentConnectionService connectionService;
    private LoginService loginService;
    private AuthorizeConsentService service;

    @BeforeEach
    void setUp() {
        clientRepository = new FakeOauthClientRepository();
        clientRepository.add(new OauthClient(
                1L, CLIENT_ID, null, "ChatGPT", OauthClient.TYPE_PUBLIC,
                "[\"" + REDIRECT_URI + "\"]", "[\"catalog:read\",\"cart:read\"]", null,
                OauthClient.STATUS_ACTIVE, null));
        requestRepository = new FakeOauthAuthorizationRequestRepository();
        connectionService = mock(AgentConnectionService.class);
        loginService = mock(LoginService.class);
        service = new AuthorizeConsentService(
                clientRepository, requestRepository, connectionService, loginService,
                new ObjectMapper(), Clock.fixed(BASE_INSTANT, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("정상 요청은 대기 상태 행을 만들고 요청 scope를 그대로 돌려준다")
    void start_validRequest_createsPendingRequest() {
        AuthorizeStartResult result = service.start(CLIENT_ID, REDIRECT_URI, "catalog:read", "state-1",
                CODE_CHALLENGE, "S256");

        AuthorizeStartResult.Started started = assertInstanceOf(AuthorizeStartResult.Started.class, result);
        assertEquals("ChatGPT", started.clientName());
        assertEquals(1, requestRepository.rows.size());
    }

    @Test
    @DisplayName("알 수 없는 client_id는 redirect 없이 거부한다")
    void start_unknownClient_rejectedWithoutRedirect() {
        AuthorizeStartResult result = service.start("unknown-client", REDIRECT_URI, "catalog:read", "state-1",
                CODE_CHALLENGE, "S256");

        assertInstanceOf(AuthorizeStartResult.Rejected.class, result);
    }

    @Test
    @DisplayName("등록되지 않은 redirect_uri는 redirect 없이 거부한다")
    void start_unregisteredRedirectUri_rejectedWithoutRedirect() {
        AuthorizeStartResult result = service.start(CLIENT_ID, "https://evil.example.com", "catalog:read",
                "state-1", CODE_CHALLENGE, "S256");

        assertInstanceOf(AuthorizeStartResult.Rejected.class, result);
    }

    @Test
    @DisplayName("허용 범위를 넘는 scope는 redirect_uri로 error를 돌려준다")
    void start_scopeExceedsAllowed_redirectsWithError() {
        AuthorizeStartResult result = service.start(CLIENT_ID, REDIRECT_URI, "purchase:handoff", "state-1",
                CODE_CHALLENGE, "S256");

        AuthorizeStartResult.ScopeRejected rejected =
                assertInstanceOf(AuthorizeStartResult.ScopeRejected.class, result);
        assertEquals(REDIRECT_URI, rejected.redirectUri());
    }

    @Test
    @DisplayName("code_challenge_method가 S256이 아니면 거부한다")
    void start_nonS256ChallengeMethod_rejected() {
        AuthorizeStartResult result = service.start(CLIENT_ID, REDIRECT_URI, "catalog:read", "state-1",
                CODE_CHALLENGE, "plain");

        assertInstanceOf(AuthorizeStartResult.Rejected.class, result);
    }

    @Test
    @DisplayName("승인하면 connection을 교체하고 code를 발급해 redirect_uri로 돌려준다")
    void decide_approve_issuesCodeAndReplacesConnection() {
        UUID requestId = startPendingRequest();
        when(loginService.login("user1", "password"))
                .thenReturn(new LoginResult(UUID.randomUUID(), 42L, "user1", "테스트"));
        when(connectionService.replaceOnAuthorization(anyLong(), anyLong(), anySet()))
                .thenReturn(new AgentConnection(
                        99L, UUID.randomUUID(), 42L, 1L, "[\"catalog:read\"]",
                        BASE_INSTANT, null, null, BASE_INSTANT, BASE_INSTANT));

        AuthorizeDecisionResult result = service.decide(requestId, "user1", "password", "APPROVE");

        AuthorizeDecisionResult.Approved approved =
                assertInstanceOf(AuthorizeDecisionResult.Approved.class, result);
        assertEquals(REDIRECT_URI, approved.redirectUri());
        assertNotNull(approved.code());
        verify(connectionService).replaceOnAuthorization(42L, 1L, Set.of("catalog:read"));
    }

    @Test
    @DisplayName("거부하면 connection을 건드리지 않고 access_denied로 redirect한다")
    void decide_deny_doesNotTouchConnection() {
        UUID requestId = startPendingRequest();

        AuthorizeDecisionResult result = service.decide(requestId, "user1", "password", "DENY");

        AuthorizeDecisionResult.Denied denied = assertInstanceOf(AuthorizeDecisionResult.Denied.class, result);
        assertEquals(REDIRECT_URI, denied.redirectUri());
        verify(connectionService, org.mockito.Mockito.never())
                .replaceOnAuthorization(anyLong(), anyLong(), anySet());
    }

    @Test
    @DisplayName("로그인 실패는 같은 화면을 에러와 함께 다시 보여준다(리다이렉트하지 않음)")
    void decide_loginFailure_rendersFormAgain() {
        UUID requestId = startPendingRequest();
        when(loginService.login("user1", "wrong-password"))
                .thenThrow(new CoreException(CoreErrorCode.AUTH_INVALID_CREDENTIALS));

        AuthorizeDecisionResult result = service.decide(requestId, "user1", "wrong-password", "APPROVE");

        assertInstanceOf(AuthorizeDecisionResult.LoginFailed.class, result);
    }

    @Test
    @DisplayName("만료된 요청은 NotFound로 처리한다")
    void decide_expiredRequest_returnsNotFound() {
        AuthorizeStartResult started = service.start(CLIENT_ID, REDIRECT_URI, "catalog:read", "state-1",
                CODE_CHALLENGE, "S256");
        UUID requestId = ((AuthorizeStartResult.Started) started).requestId();

        Clock later = Clock.fixed(BASE_INSTANT.plus(Duration.ofMinutes(11)), ZoneOffset.UTC);
        AuthorizeConsentService expiredService = new AuthorizeConsentService(
                clientRepository, requestRepository, connectionService, loginService, new ObjectMapper(), later);

        AuthorizeDecisionResult result = expiredService.decide(requestId, "user1", "password", "APPROVE");

        assertInstanceOf(AuthorizeDecisionResult.NotFound.class, result);
    }

    private UUID startPendingRequest() {
        AuthorizeStartResult result = service.start(CLIENT_ID, REDIRECT_URI, "catalog:read", "state-1",
                CODE_CHALLENGE, "S256");
        return ((AuthorizeStartResult.Started) result).requestId();
    }
}
