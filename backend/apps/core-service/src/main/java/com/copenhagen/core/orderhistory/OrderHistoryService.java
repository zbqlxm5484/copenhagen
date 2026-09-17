package com.copenhagen.core.orderhistory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.copenhagen.core.common.crypto.PiiEncryptor;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.orderhistory.OrderHistoryRepository.OrderCursor;
import com.copenhagen.core.orderhistory.OrderHistoryRepository.OrderItemRow;
import com.copenhagen.core.orderhistory.OrderHistoryRepository.OrderRow;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderHistoryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String PAYMENT_LABEL_FALLBACK = "Copenhagen Pay";
    private static final Set<String> ORDER_STATUSES = Set.of(
            "CREATED", "CONFIRMED", "FULFILLING", "SHIPPED", "DELIVERED", "CANCELLED", "FAILED");

    private final OrderHistoryRepository repository;
    private final PiiEncryptor piiEncryptor;
    private final ObjectMapper objectMapper;

    public OrderHistoryService(
            OrderHistoryRepository repository, PiiEncryptor piiEncryptor, ObjectMapper objectMapper) {
        this.repository = repository;
        this.piiEncryptor = piiEncryptor;
        this.objectMapper = objectMapper;
    }

    public OrderPageResponse list(long userId, Integer requestedSize, String encodedCursor, String requestedStatus) {
        int pageSize = pageSize(requestedSize);
        String status = status(requestedStatus);
        OrderCursor cursor = decodeCursor(encodedCursor);
        List<OrderRow> fetched = repository.findPage(userId, status, cursor, pageSize + 1);
        boolean hasNext = fetched.size() > pageSize;
        List<OrderRow> page = hasNext ? fetched.subList(0, pageSize) : fetched;
        Map<Long, List<OrderItemRow>> itemsBySnapshot = itemsBySnapshot(page);
        List<OrderResponse> items = page.stream()
                .map(row -> toResponse(row, itemsBySnapshot.getOrDefault(row.checkoutSnapshotId(), List.of())))
                .toList();
        String nextCursor = hasNext ? encodeCursor(page.getLast()) : null;
        return new OrderPageResponse(items, nextCursor);
    }

    public OrderResponse get(long userId, UUID orderId) {
        OrderRow row = repository.findByPublicIdAndUserId(orderId, userId)
                .orElseThrow(() -> new CoreException(CoreErrorCode.ORDER_NOT_FOUND));
        List<OrderItemRow> items = repository.findItemsBySnapshotIds(List.of(row.checkoutSnapshotId()));
        return toResponse(row, items);
    }

    private Map<Long, List<OrderItemRow>> itemsBySnapshot(List<OrderRow> orders) {
        List<Long> snapshotIds = orders.stream().map(OrderRow::checkoutSnapshotId).toList();
        Map<Long, List<OrderItemRow>> grouped = new LinkedHashMap<>();
        for (OrderItemRow item : repository.findItemsBySnapshotIds(snapshotIds)) {
            grouped.computeIfAbsent(item.checkoutSnapshotId(), ignored -> new java.util.ArrayList<>()).add(item);
        }
        return grouped;
    }

    private OrderResponse toResponse(OrderRow row, List<OrderItemRow> itemRows) {
        List<ItemSummary> items = itemRows.stream()
                .map(item -> new ItemSummary(item.name(), option(item.selectedOptions()), item.quantity()))
                .toList();
        return new OrderResponse(
                row.publicId(),
                title(items),
                row.status(),
                row.amountMinor(),
                row.currency(),
                row.createdAt(),
                items,
                new AddressSnapshot(
                        emptyIfNull(piiEncryptor.decrypt(row.recipient())),
                        emptyIfNull(piiEncryptor.decrypt(row.phone())),
                        emptyIfNull(piiEncryptor.decrypt(row.postalCode())),
                        emptyIfNull(piiEncryptor.decrypt(row.address())),
                        emptyIfNull(piiEncryptor.decrypt(row.detail())),
                        emptyIfNull(piiEncryptor.decrypt(row.memo()))),
                emptyIfNull(row.paymentLabel(), PAYMENT_LABEL_FALLBACK));
    }

    private String option(String selectedOptions) {
        if (selectedOptions == null || selectedOptions.isBlank()) {
            return "";
        }
        try {
            return objectMapper.readValue(selectedOptions, new TypeReference<List<SelectedOption>>() { })
                    .stream()
                    .map(SelectedOption::label)
                    .filter(Objects::nonNull)
                    .filter(label -> !label.isBlank())
                    .collect(java.util.stream.Collectors.joining(" / "));
        } catch (JacksonException exception) {
            throw new CoreException(
                    CoreErrorCode.INTERNAL_ERROR,
                    CoreErrorCode.INTERNAL_ERROR.defaultMessage(),
                    null,
                    CoreErrorCode.INTERNAL_ERROR.defaultNextAction(),
                    exception);
        }
    }

    private static String title(List<ItemSummary> items) {
        if (items.isEmpty()) {
            return "주문 상품";
        }
        return items.size() == 1
                ? items.getFirst().name()
                : items.getFirst().name() + " 외 " + (items.size() - 1) + "건";
    }

    private static int pageSize(Integer requestedSize) {
        int pageSize = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new CoreException(CoreErrorCode.INVALID_REQUEST);
        }
        return pageSize;
    }

    private static String status(String requestedStatus) {
        if (requestedStatus == null || requestedStatus.isBlank()) {
            return null;
        }
        if (!ORDER_STATUSES.contains(requestedStatus)) {
            throw new CoreException(CoreErrorCode.INVALID_REQUEST);
        }
        return requestedStatus;
    }

    private static String encodeCursor(OrderRow row) {
        String value = row.createdAt().getEpochSecond() + ":" + row.createdAt().getNano() + ":" + row.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static OrderCursor decodeCursor(String encodedCursor) {
        if (encodedCursor == null || encodedCursor.isBlank()) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(encodedCursor), StandardCharsets.UTF_8)
                    .split(":", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("cursor parts");
            }
            return new OrderCursor(Instant.ofEpochSecond(Long.parseLong(parts[0]), Long.parseLong(parts[1])),
                    Long.parseLong(parts[2]));
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw new CoreException(
                    CoreErrorCode.INVALID_REQUEST,
                    CoreErrorCode.INVALID_REQUEST.defaultMessage(),
                    null,
                    CoreErrorCode.INVALID_REQUEST.defaultNextAction(),
                    exception);
        }
    }

    private static String emptyIfNull(String value) {
        return emptyIfNull(value, "");
    }

    private static String emptyIfNull(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private record SelectedOption(String optionValueId, String name, String label) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderPageResponse(List<OrderResponse> items, String nextCursor) {
    }

    public record OrderResponse(
            UUID orderId,
            String title,
            String status,
            long amountMinor,
            String currency,
            Instant createdAt,
            List<ItemSummary> items,
            AddressSnapshot address,
            String paymentLabel) {
    }

    public record ItemSummary(String name, String option, int quantity) {
    }

    public record AddressSnapshot(
            String recipient,
            String phone,
            String postalCode,
            String address,
            String detail,
            String memo) {
    }
}
