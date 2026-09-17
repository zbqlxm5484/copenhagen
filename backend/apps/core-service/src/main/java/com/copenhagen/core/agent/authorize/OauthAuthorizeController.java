package com.copenhagen.core.agent.authorize;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AGENT-03: 앱 계정 연동. Core가 로그인+동의 화면을 직접 서빙한다(설계서 §1.3). 이 컨트롤러는 공개
 * 엔드포인트라 {@code CoreAuthenticationFilter} 보호 대상에서 제외되어 있다({@code CoreAuthenticationPolicy}).
 * 예외를 {@code GlobalExceptionHandler}(JSON 전용)로 흘려보내지 않기 위해 서비스 결과를 분기 처리해
 * HTML/302 응답을 직접 만든다.
 */
@RestController
@RequestMapping("/oauth/authorize")
public class OauthAuthorizeController {

    private final AuthorizeConsentService consentService;

    public OauthAuthorizeController(AuthorizeConsentService consentService) {
        this.consentService = consentService;
    }

    @GetMapping
    public ResponseEntity<String> authorize(
            @RequestParam("client_id") String clientId,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam("scope") String scope,
            @RequestParam("state") String state,
            @RequestParam("code_challenge") String codeChallenge,
            @RequestParam("code_challenge_method") String codeChallengeMethod) {
        AuthorizeStartResult result =
                consentService.start(clientId, redirectUri, scope, state, codeChallenge, codeChallengeMethod);
        return switch (result) {
            case AuthorizeStartResult.Rejected rejected -> htmlError(rejected.message());
            case AuthorizeStartResult.ScopeRejected scopeRejected ->
                    redirectWithError(scopeRejected.redirectUri(), "invalid_scope", scopeRejected.state());
            case AuthorizeStartResult.Started started -> ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(ConsentPageRenderer.renderConsent(
                            started.requestId(), started.clientName(), started.logoUrl(), started.scopes(), null));
        };
    }

    @PostMapping("/{requestId}/decision")
    public ResponseEntity<String> decide(
            @PathVariable UUID requestId,
            @RequestParam("loginId") String loginId,
            @RequestParam("password") String password,
            @RequestParam("decision") String decision) {
        AuthorizeDecisionResult result = consentService.decide(requestId, loginId, password, decision);
        return switch (result) {
            case AuthorizeDecisionResult.NotFound notFound -> htmlError(notFound.message());
            case AuthorizeDecisionResult.Denied denied ->
                    redirectWithError(denied.redirectUri(), "access_denied", denied.state());
            case AuthorizeDecisionResult.LoginFailed loginFailed -> ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(ConsentPageRenderer.renderConsent(
                            loginFailed.requestId(), loginFailed.clientName(), loginFailed.logoUrl(),
                            loginFailed.scopes(), loginFailed.message()));
            case AuthorizeDecisionResult.Approved approved -> redirectWithCode(approved);
        };
    }

    private static ResponseEntity<String> htmlError(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.TEXT_HTML)
                .body(ConsentPageRenderer.renderError(message));
    }

    private static ResponseEntity<String> redirectWithError(String redirectUri, String error, String state) {
        String location = redirectUri + (redirectUri.contains("?") ? "&" : "?")
                + "error=" + encode(error) + "&state=" + encode(state);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    private static ResponseEntity<String> redirectWithCode(AuthorizeDecisionResult.Approved approved) {
        String redirectUri = approved.redirectUri();
        String location = redirectUri + (redirectUri.contains("?") ? "&" : "?")
                + "code=" + encode(approved.code()) + "&state=" + encode(approved.state());
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
