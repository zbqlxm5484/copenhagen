package com.copenhagen.pay.payment.merchant;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class HttpMerchantPublicKeyClient implements MerchantPublicKeyClient {

    private final MerchantServiceProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI profileUri;

    public HttpMerchantPublicKeyClient(
            MerchantServiceProperties properties,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMillis()))
                .build();
        this.profileUri = createProfileUri(properties.baseUrl(), properties.profilePath());
    }

    @Override
    public List<Map<String, Object>> findPublicKeys(String merchantId) {
        HttpRequest request = HttpRequest.newBuilder(profileUri)
                .timeout(Duration.ofMillis(properties.requestTimeoutMillis()))
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MerchantKeyLookupException(
                        "Merchant 공개키 조회가 HTTP " + response.statusCode() + "로 실패했습니다.");
            }
            MerchantProfileResponse profile = objectMapper.readValue(
                    response.body(),
                    MerchantProfileResponse.class);
            if (profile == null) {
                throw new MerchantKeyLookupException("Merchant Profile 응답이 비어 있습니다.");
            }
            return profile.keys();
        } catch (MerchantKeyLookupException exception) {
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw new MerchantKeyLookupException("Merchant 공개키 조회 시간이 초과되었습니다.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MerchantKeyLookupException("Merchant 공개키 조회가 중단되었습니다.", exception);
        } catch (IOException | JacksonException | IllegalArgumentException exception) {
            throw new MerchantKeyLookupException("Merchant 공개키 응답을 읽을 수 없습니다.", exception);
        }
    }

    private URI createProfileUri(String baseUrl, String profilePath) {
        try {
            return URI.create(baseUrl.replaceAll("/+$", "") + profilePath);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("services.merchant.base-url must be a valid URI", exception);
        }
    }
}
