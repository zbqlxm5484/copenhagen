package com.copenhagen.core.auth.profile;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.copenhagen.core.auth.signup.SignupService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/core/v1/users/me}가 순서 10에서 처음 생기는 실제 보호 라우트임을 실 PostgreSQL로 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CORE_TEST_DB_URL", matches = ".+")
class UserProfileApiIntegrationTest {

    private static final String LOGIN_ID = "apiprofile1";
    private static final String PASSWORD = "Password1!";
    private static final String NAME = "홍길동";

    @DynamicPropertySource
    static void coreTestDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CORE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("CORE_TEST_DB_PASSWORD"));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SignupService signupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabaseAndSeedAccount() {
        jdbcTemplate.update(
                "TRUNCATE users, merchants, oauth_clients, idempotency_records RESTART IDENTITY CASCADE");
        signupService.signup(LOGIN_ID, PASSWORD, "api-profile@example.com", NAME);
    }

    @Test
    @DisplayName("유효한 Access Token이면 가입 시 저장한 loginId/name을 그대로 반환한다")
    void me_validAccessToken_returnsSignedUpProfile() throws Exception {
        String accessToken = login();

        mockMvc.perform(get("/api/core/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value(LOGIN_ID))
                .andExpect(jsonPath("$.name").value(NAME))
                .andExpect(jsonPath("$.userId").isNotEmpty());
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 401 AUTH_TOKEN_MISSING이다")
    void me_missingHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/core/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_TOKEN_MISSING"));
    }

    @Test
    @DisplayName("위조되거나 만료된 토큰이면 401 AUTH_TOKEN_INVALID다")
    void me_invalidToken_returns401() throws Exception {
        mockMvc.perform(get("/api/core/v1/users/me")
                        .header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_TOKEN_INVALID"));
    }

    private String login() throws Exception {
        String body = """
                {"loginId":"%s","password":"%s","deviceId":"device-1","deviceName":"iPhone 15"}
                """.formatted(LOGIN_ID, PASSWORD);
        String response = mockMvc.perform(post("/api/core/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }
}
