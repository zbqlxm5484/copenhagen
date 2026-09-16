package com.copenhagen.merchantmock.webhook;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.Executor;

import com.copenhagen.merchantmock.order.Order;
import com.copenhagen.merchantmock.order.OrderEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 주문 사건을 발송 대장에 적고 커밋 뒤 첫 발송을 예약한다(DESIGN.md 8.6절). Outbox 다.
 *
 * <p>{@code OrderService.create} 의 트랜잭션 안에서 불린다. 여기서 하는 I/O 는 INSERT 하나다. HTTP 는
 * 커밋 뒤 별도 스레드다. Complete 응답이 웹훅 왕복을 기다리지 않게 하기 위해서다. 트랜잭션이 없으면(테스트)
 * 바로 executor 에 넘긴다.
 *
 * <p>페이로드는 Core 확정본 5.2 다. {@code {"order": <orders.raw_order_json>}}. 안쪽은 주문 조회 응답과
 * 같은 객체다. UCP 명세의 전체 스냅샷과 다른 점은 11절에 적었다.
 */
@Component
public class OrderWebhookPublisher implements OrderEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(OrderWebhookPublisher.class);

    private final WebhookRepository repository;
    private final WebhookDispatcher dispatcher;
    private final Executor executor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OrderWebhookPublisher(WebhookRepository repository, WebhookDispatcher dispatcher,
            @Qualifier(WebhookConfig.EXECUTOR) Executor executor, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void orderCreated(Order order) {
        enqueue(order, WebhookEventType.ORDER_CREATED);
    }

    @Override
    public void orderUpdated(Order order) {
        enqueue(order, WebhookEventType.ORDER_UPDATED);
    }

    private void enqueue(Order order, WebhookEventType eventType) {
        OffsetDateTime now = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        WebhookDelivery delivery = WebhookDelivery.pending(
                UUID.randomUUID().toString(), order.id(), eventType, payloadOf(order), now);
        repository.insert(delivery);
        LOG.info("웹훅을 대장에 적었다. webhookId={} orderId={} event={}", delivery.webhookId(), order.id(),
                eventType.value());
        afterCommit(() -> dispatcher.dispatch(delivery.webhookId()));
    }

    private String payloadOf(Order order) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("order", objectMapper.readTree(order.rawOrderJson()));
        return objectMapper.writeValueAsString(root);
    }

    private void afterCommit(Runnable send) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(send);
                }
            });
            return;
        }
        executor.execute(send);
    }
}
