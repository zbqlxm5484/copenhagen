package com.copenhagen.core.agent.authorize;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.copenhagen.core.common.auth.CoreAuthenticationFilter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code /oauth/authorize}는 공개 엔드포인트라 필터가 필요 없지만, {@code @WebMvcTest}가 컴포넌트
 * 스캔으로 {@code CoreAuthenticationFilter}까지 끌어와 그 생성자 의존성(JWKS 등)을 요구하므로 제외한다. */
@WebMvcTest(
        controllers = OauthAuthorizeController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = CoreAuthenticationFilter.class))
class OauthAuthorizeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthorizeConsentService consentService;

    @Test
    @DisplayName("검증 통과 시 200과 로그인+동의 폼 HTML을 반환한다")
    void authorize_started_returnsHtmlForm() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(consentService.start(eq("chatgpt-plugin"), any(), eq("catalog:read"), eq("state-1"), any(), eq("S256")))
                .thenReturn(new AuthorizeStartResult.Started(requestId, "ChatGPT", null, List.of("catalog:read")));

        mockMvc.perform(get("/oauth/authorize")
                        .param("client_id", "chatgpt-plugin")
                        .param("redirect_uri", "https://chat.openai.com/callback")
                        .param("scope", "catalog:read")
                        .param("state", "state-1")
                        .param("code_challenge", "challenge")
                        .param("code_challenge_method", "S256"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/oauth/authorize/" + requestId + "/decision")));
    }

    @Test
    @DisplayName("scope가 허용 범위를 넘으면 redirect_uri로 error=invalid_scope를 돌려준다")
    void authorize_scopeRejected_redirectsWithError() throws Exception {
        when(consentService.start(any(), any(), any(), any(), any(), any())).thenReturn(
                new AuthorizeStartResult.ScopeRejected("https://chat.openai.com/callback", "state-1"));

        mockMvc.perform(get("/oauth/authorize")
                        .param("client_id", "chatgpt-plugin")
                        .param("redirect_uri", "https://chat.openai.com/callback")
                        .param("scope", "purchase:handoff")
                        .param("state", "state-1")
                        .param("code_challenge", "challenge")
                        .param("code_challenge_method", "S256"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://chat.openai.com/callback?error=invalid_scope&state=state-1"));
    }

    @Test
    @DisplayName("승인 완료 시 redirect_uri로 code와 state를 돌려준다")
    void decide_approved_redirectsWithCode() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(consentService.decide(requestId, "user1", "password", "APPROVE")).thenReturn(
                new AuthorizeDecisionResult.Approved("https://chat.openai.com/callback", "the-code", "state-1"));

        mockMvc.perform(post("/oauth/authorize/" + requestId + "/decision")
                        .param("loginId", "user1")
                        .param("password", "password")
                        .param("decision", "APPROVE"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://chat.openai.com/callback?code=the-code&state=state-1"));
    }
}
