package com.copenhagen.merchantmock.webhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.copenhagen.merchantmock.security.HttpMessageSignature;
import com.copenhagen.merchantmock.security.HttpMessageSignature.Components;
import com.copenhagen.merchantmock.security.HttpMessageSignature.Signed;
import com.copenhagen.merchantmock.security.SigningKeyProvider;
import com.copenhagen.merchantmock.support.MerchantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@code POST {webhook_url}} 한 번(DESIGN.md 8.6절). 다이제스트 → RFC 9421 서명 → 전송 → 결과 기록.
 *
 * <p>보내는 바이트는 항상 DB 에서 다시 읽은 {@code payload_json} 이다. jsonb 가 정규화한 형태라 첫 발송과
 * 재전송이 같은 바이트다(불변 조건 8번). 서명은 매번 새로 만든다(불변 조건 7번).
 *
 * <p>2xx 면 {@code DELIVERED}. 그 밖의 응답·타임아웃·연결 실패는 {@code attempt_count + 1} 과 지수 백오프
 * 시각을 적고 {@code PENDING} 으로 남긴다. 예외를 밖으로 내지 않는다. 개인키가 없으면 보내지 않는다.
 * 서명 없는 웹훅은 Core 가 거절하므로 보내는 것이 안 보내는 것보다 나쁘다.
 */
@Component
public class HttpWebhookDispatcher implements WebhookDispatcher {

    static final String HEADER_UCP_AGENT = "UCP-Agent";
    static final String HEADER_WEBHOOK_ID = "Webhook-Id";
    static final String HEADER_WEBHOOK_TIMESTAMP = "Webhook-Timestamp";
    static final String HEADER_CONTENT_DIGEST = "Content-Digest";
    static final String HEADER_SIGNATURE_INPUT = "Signature-Input";
    static final String HEADER_SIGNATURE = "Signature";

    private static final Logger LOG = LoggerFactory.getLogger(HttpWebhookDispatcher.class);

    private final WebhookRepository repository;
    private final WebhookTargetResolver targets;
    private final SigningKeyProvider signingKeys;
    private final MerchantProperties merchant;
    private final Clock clock;
    private final RestClient restClient;

    @Autowired
    public HttpWebhookDispatcher(WebhookRepository repository, WebhookTargetResolver targets,
            SigningKeyProvider signingKeys, MerchantProperties merchant, CoreProperties core, Clock clock) {
        this(repository, targets, signingKeys, merchant, clock,
                RestClient.builder().requestFactory(requestFactory(core)));
    }

    /** 테스트 생성자. {@code MockRestServiceServer.bindTo(builder)} 로 묶은 빌더를 받는다. */
    HttpWebhookDispatcher(WebhookRepository repository, WebhookTargetResolver targets,
            SigningKeyProvider signingKeys, MerchantProperties merchant, Clock clock, RestClient.Builder builder) {
        this.repository = repository;
        this.targets = targets;
        this.signingKeys = signingKeys;
        this.merchant = merchant;
        this.clock = clock;
        this.restClient = builder.build();
    }

    @Override
    public Result dispatch(String webhookId) {
        Optional<WebhookDelivery> found = repository.findById(webhookId);
        if (found.isEmpty() || found.get().status() != WebhookStatus.PENDING) {
            LOG.info("보낼 것이 없다. webhookId={} status={}", webhookId,
                    found.map(WebhookDelivery::status).orElse(null));
            return Result.SKIPPED_NOT_PENDING;
        }
        WebhookDelivery delivery = found.get();
        if (!signingKeys.canSign()) {
            LOG.warn("서명 개인키가 없어 웹훅을 보내지 않는다. PENDING 으로 남긴다. webhookId={} orderId={}",
                    webhookId, delivery.orderId());
            return Result.SKIPPED_NO_SIGNING_KEY;
        }

        URI target = targets.resolve();
        byte[] body = delivery.payloadJson().getBytes(StandardCharsets.UTF_8);
        String contentDigest = HttpMessageSignature.contentDigest(body);
        OffsetDateTime now = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Components components = new Components("POST", authorityOf(target), target.getRawPath(),
                contentDigest, MediaType.APPLICATION_JSON_VALUE);
        Signed signed = HttpMessageSignature.sign(components, now.toEpochSecond(), signingKeys.requireSigningKey());
        WebhookDelivery.Signed record = new WebhookDelivery.Signed(signed.signatureInput(), signed.signature(),
                contentDigest);

        Integer responseStatus = null;
        try {
            HttpStatusCode status = restClient.post()
                    .uri(target)
                    .header(HEADER_UCP_AGENT, "profile=\"" + merchant.publicBaseUrl() + "/.well-known/ucp\"")
                    .header(HEADER_WEBHOOK_ID, delivery.webhookId())
                    .header(HEADER_WEBHOOK_TIMESTAMP, now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HEADER_CONTENT_DIGEST, contentDigest)
                    .header(HEADER_SIGNATURE_INPUT, signed.signatureInput())
                    .header(HEADER_SIGNATURE, signed.signature())
                    .body(body)
                    .exchange((request, response) -> response.getStatusCode());
            responseStatus = status.value();
            if (status.is2xxSuccessful()) {
                repository.markDelivered(webhookId, status.value(), record);
                LOG.info("웹훅 전달. webhookId={} orderId={} event={} http={}", webhookId, delivery.orderId(),
                        delivery.eventType().value(), status.value());
                return Result.DELIVERED;
            }
            LOG.warn("웹훅이 거절됐다. 재시도를 예약한다. webhookId={} http={}", webhookId, status.value());
        } catch (RuntimeException exception) {
            LOG.warn("웹훅을 보내지 못했다. 재시도를 예약한다. webhookId={} target={} cause={}", webhookId, target,
                    exception.toString());
        }
        OffsetDateTime nextAttemptAt = now.plus(backoff(delivery.attemptCount() + 1));
        repository.markRetry(webhookId, responseStatus, nextAttemptAt, record);
        return Result.RETRY_SCHEDULED;
    }

    /** {@code initial × 2^(attempt-1)}. 1회 실패 뒤 10초, 2회 20초, 3회 40초(DESIGN.md 7.4절). */
    Duration backoff(int attempt) {
        Duration initial = merchant.webhook().retryInitialDelay();
        return initial.multipliedBy(1L << Math.max(0, Math.min(attempt - 1, 20)));
    }

    /** RFC 9421 {@code @authority}. Host 헤더 값과 같다. 기본 포트가 아니면 포트가 붙는다. */
    static String authorityOf(URI target) {
        return target.getPort() == -1 ? target.getHost() : target.getHost() + ":" + target.getPort();
    }

    private static JdkClientHttpRequestFactory requestFactory(CoreProperties core) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(core.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(core.requestTimeout());
        return factory;
    }
}
