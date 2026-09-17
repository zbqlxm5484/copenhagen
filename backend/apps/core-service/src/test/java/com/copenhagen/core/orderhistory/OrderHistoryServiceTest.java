package com.copenhagen.core.orderhistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.copenhagen.core.common.crypto.PiiEncryptor;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.orderhistory.OrderHistoryRepository.OrderCursor;
import com.copenhagen.core.orderhistory.OrderHistoryRepository.OrderItemRow;
import com.copenhagen.core.orderhistory.OrderHistoryRepository.OrderRow;

import tools.jackson.databind.json.JsonMapper;

class OrderHistoryServiceTest {

    private static final long USER_ID = 7L;

    @Test
    void list_usesStableCursorAndProjectsSnapshotFields() {
        OrderHistoryRepository repository = org.mockito.Mockito.mock(OrderHistoryRepository.class);
        PiiEncryptor encryptor = encryptor();
        OrderHistoryService service = new OrderHistoryService(repository, encryptor, JsonMapper.builder().build());
        Instant createdAt = Instant.parse("2026-09-18T08:00:00.123456789Z");
        OrderRow first = row(encryptor, 30L, UUID.randomUUID(), createdAt, null);
        OrderRow second = row(encryptor, 29L, UUID.randomUUID(), createdAt.minusSeconds(1), "VISA **** 1234");
        when(repository.findPage(eq(USER_ID), eq("CREATED"), any(), eq(2))).thenReturn(List.of(first, second));
        when(repository.findItemsBySnapshotIds(List.of(100L))).thenReturn(List.of(
                new OrderItemRow(100L, "상품 A", "[{\"label\":\"화이트\"},{\"label\":\"M\"}]", 1)));

        OrderHistoryService.OrderPageResponse response = service.list(USER_ID, 1, null, "CREATED");

        assertEquals(1, response.items().size());
        assertEquals("상품 A", response.items().getFirst().title());
        assertEquals("화이트 / M", response.items().getFirst().items().getFirst().option());
        assertEquals("홍길동", response.items().getFirst().address().recipient());
        assertEquals("Copenhagen Pay", response.items().getFirst().paymentLabel());
        assertFalse(response.nextCursor().isBlank());

        when(repository.findPage(eq(USER_ID), eq("CREATED"), any(), eq(2))).thenReturn(List.of());
        service.list(USER_ID, 1, response.nextCursor(), "CREATED");

        ArgumentCaptor<OrderCursor> cursor = ArgumentCaptor.forClass(OrderCursor.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(2))
                .findPage(eq(USER_ID), eq("CREATED"), cursor.capture(), eq(2));
        assertEquals(createdAt, cursor.getAllValues().get(1).createdAt());
        assertEquals(30L, cursor.getAllValues().get(1).orderId());
    }

    @Test
    void list_rejectsMalformedCursorAndUnknownStatus() {
        OrderHistoryRepository repository = org.mockito.Mockito.mock(OrderHistoryRepository.class);
        OrderHistoryService service = new OrderHistoryService(repository, encryptor(), JsonMapper.builder().build());

        assertThrows(CoreException.class, () -> service.list(USER_ID, 20, "not-a-cursor", null));
        assertThrows(CoreException.class, () -> service.list(USER_ID, 20, null, "PAID"));
    }

    private static OrderRow row(
            PiiEncryptor encryptor, long id, UUID publicId, Instant createdAt, String paymentLabel) {
        return new OrderRow(
                id,
                publicId,
                100L,
                "CREATED",
                87000L,
                "KRW",
                paymentLabel,
                createdAt,
                encryptor.encrypt("홍길동"),
                encryptor.encrypt("01012345678"),
                encryptor.encrypt("06236"),
                encryptor.encrypt("서울특별시 강남구 테헤란로 1"),
                encryptor.encrypt("101동 1001호"),
                encryptor.encrypt("문 앞에 놓아주세요"));
    }

    private static PiiEncryptor encryptor() {
        return new PiiEncryptor(Base64.getEncoder().encodeToString(new byte[32]));
    }
}
