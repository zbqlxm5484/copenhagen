package com.copenhagen.core.orderhistory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.copenhagen.core.common.crypto.EncryptedPii;

public interface OrderHistoryRepository {

    List<OrderRow> findPage(long userId, String status, OrderCursor cursor, int limit);

    Optional<OrderRow> findByPublicIdAndUserId(UUID publicId, long userId);

    List<OrderItemRow> findItemsBySnapshotIds(List<Long> checkoutSnapshotIds);

    record OrderCursor(Instant createdAt, long orderId) {
    }

    record OrderRow(
            long id,
            UUID publicId,
            long checkoutSnapshotId,
            String status,
            long amountMinor,
            String currency,
            String paymentLabel,
            Instant createdAt,
            EncryptedPii recipient,
            EncryptedPii phone,
            EncryptedPii postalCode,
            EncryptedPii address,
            EncryptedPii detail,
            EncryptedPii memo) {
    }

    record OrderItemRow(long checkoutSnapshotId, String name, String selectedOptions, int quantity) {
    }
}
