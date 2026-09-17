package com.copenhagen.core.checkout;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.copenhagen.core.common.crypto.EncryptedPii;

@Repository
public class JdbcMerchantCheckoutRepository implements MerchantCheckoutRepository {

    private static final String INSERT_CHECKOUT_SQL = """
            INSERT INTO merchant_checkouts
                (purchase_request_id, merchant_id, merchant_checkout_id, checkout_digest, status,
                    checkout_created_at, checkout_expires_at, last_synced_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private static final String INSERT_SNAPSHOT_SQL = """
            INSERT INTO checkout_snapshots
                (public_id, merchant_checkout_id, checkout_digest, merchant_checkout_payload,
                    subtotal_amount, discount_amount, tax_amount, shipping_fee_amount, total_amount,
                    currency, recipient_name_encrypted, recipient_phone_encrypted, postal_code_encrypted,
                    address_line1_encrypted, address_line2_encrypted, delivery_request_encrypted,
                    expires_at, created_at)
            VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private static final String INSERT_SNAPSHOT_ITEM_SQL = """
            INSERT INTO checkout_snapshot_items
                (checkout_snapshot_id, merchant_product_id, merchant_variant_id, product_name, product_url,
                    selected_options, quantity, unit_price_amount, discount_amount, line_total_amount,
                    currency)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_FULFILLMENT_OPTION_SQL = """
            INSERT INTO checkout_fulfillment_option_snapshots
                (checkout_snapshot_id, fulfillment_group_key, shipping_option_id, shipping_option_name,
                    delivery_method, shipping_fee_amount, estimated_delivery_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcMerchantCheckoutRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public List<Long> insertAll(List<MerchantCheckoutRecord> records, Instant now) {
        List<Long> checkoutIds = new ArrayList<>(records.size());
        for (MerchantCheckoutRecord record : records) {
            long checkoutId = insertCheckout(record.checkout(), now);
            long snapshotId = insertSnapshot(record.snapshot(), checkoutId, now);
            for (CheckoutSnapshotItem item : record.items()) {
                insertItem(snapshotId, item);
            }
            for (CheckoutFulfillmentOption option : record.fulfillmentOptions()) {
                insertFulfillmentOption(snapshotId, option);
            }
            checkoutIds.add(checkoutId);
        }
        return List.copyOf(checkoutIds);
    }

    @Override
    public void markAwaitingApproval(
            long id, String paymentApprovalId, Instant paymentApprovalExpiresAt, Instant now) {
        jdbcTemplate.update(
                """
                UPDATE merchant_checkouts
                SET status = 'AWAITING_APPROVAL', payment_approval_id = ?,
                    payment_approval_expires_at = ?, updated_at = ?
                WHERE id = ? AND status = 'CREATING'
                """,
                paymentApprovalId,
                Timestamp.from(paymentApprovalExpiresAt),
                Timestamp.from(now),
                id);
    }

    @Override
    public List<AwaitingApprovalCheckout> findAwaitingApproval() {
        return jdbcTemplate.query(
                """
                SELECT mc.id, mc.purchase_request_id, pr.user_id, mc.merchant_id,
                       mc.merchant_checkout_id, mc.payment_approval_id, mc.payment_approval_expires_at,
                       cs.id AS checkout_snapshot_id, cs.total_amount, cs.currency
                FROM merchant_checkouts mc
                JOIN purchase_requests pr ON pr.id = mc.purchase_request_id
                JOIN checkout_snapshots cs ON cs.merchant_checkout_id = mc.id
                WHERE mc.status = 'AWAITING_APPROVAL'
                """,
                JdbcMerchantCheckoutRepository::mapAwaitingApproval);
    }

    @Override
    public boolean beginCompleting(long id, Instant now) {
        int updated = jdbcTemplate.update(
                """
                UPDATE merchant_checkouts
                SET status = 'COMPLETING', updated_at = ?
                WHERE id = ? AND status = 'AWAITING_APPROVAL'
                """,
                Timestamp.from(now),
                id);
        return updated == 1;
    }

    @Override
    public void markCompleted(long id, Instant now) {
        jdbcTemplate.update(
                """
                UPDATE merchant_checkouts
                SET status = 'COMPLETED', completed_at = ?, updated_at = ?
                WHERE id = ?
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                id);
    }

    @Override
    public void markCancelled(long id, String failureCode, Instant now) {
        jdbcTemplate.update(
                """
                UPDATE merchant_checkouts
                SET status = 'CANCELLED', failure_code = ?, cancelled_at = ?, updated_at = ?
                WHERE id = ?
                """,
                failureCode,
                Timestamp.from(now),
                Timestamp.from(now),
                id);
    }

    @Override
    public void markExpired(long id, Instant now) {
        jdbcTemplate.update(
                """
                UPDATE merchant_checkouts
                SET status = 'EXPIRED', updated_at = ?
                WHERE id = ?
                """,
                Timestamp.from(now),
                id);
    }

    @Override
    public void markFailed(long id, String failureCode, Instant now) {
        jdbcTemplate.update(
                """
                UPDATE merchant_checkouts
                SET status = 'FAILED', failure_code = ?, updated_at = ?
                WHERE id = ?
                """,
                failureCode,
                Timestamp.from(now),
                id);
    }

    private static AwaitingApprovalCheckout mapAwaitingApproval(
            ResultSet resultSet, int rowNumber) throws SQLException {
        return new AwaitingApprovalCheckout(
                resultSet.getLong("id"),
                resultSet.getLong("purchase_request_id"),
                resultSet.getLong("user_id"),
                resultSet.getLong("merchant_id"),
                resultSet.getString("merchant_checkout_id"),
                resultSet.getString("payment_approval_id"),
                resultSet.getTimestamp("payment_approval_expires_at").toInstant(),
                resultSet.getLong("checkout_snapshot_id"),
                resultSet.getLong("total_amount"),
                resultSet.getString("currency"));
    }

    private long insertCheckout(MerchantCheckout checkout, Instant now) {
        Long id = jdbcTemplate.queryForObject(
                INSERT_CHECKOUT_SQL,
                Long.class,
                checkout.purchaseRequestId(),
                checkout.merchantId(),
                checkout.merchantCheckoutId(),
                checkout.checkoutDigest(),
                checkout.status(),
                Timestamp.from(checkout.checkoutCreatedAt()),
                toTimestamp(checkout.checkoutExpiresAt()),
                Timestamp.from(now),
                Timestamp.from(now));
        return id == null ? 0L : id;
    }

    private long insertSnapshot(CheckoutSnapshot snapshot, long merchantCheckoutId, Instant now) {
        Long id = jdbcTemplate.queryForObject(
                INSERT_SNAPSHOT_SQL,
                Long.class,
                snapshot.publicId(),
                merchantCheckoutId,
                snapshot.checkoutDigest(),
                snapshot.payload(),
                snapshot.subtotalAmount(),
                snapshot.discountAmount(),
                snapshot.taxAmount(),
                snapshot.shippingFeeAmount(),
                snapshot.totalAmount(),
                snapshot.currency(),
                toBytes(snapshot.recipientNameEncrypted()),
                toBytes(snapshot.recipientPhoneEncrypted()),
                toBytes(snapshot.postalCodeEncrypted()),
                toBytes(snapshot.addressLine1Encrypted()),
                toBytes(snapshot.addressLine2Encrypted()),
                toBytes(snapshot.deliveryRequestEncrypted()),
                toTimestamp(snapshot.expiresAt()),
                Timestamp.from(now));
        return id == null ? 0L : id;
    }

    private void insertItem(long snapshotId, CheckoutSnapshotItem item) {
        jdbcTemplate.update(
                INSERT_SNAPSHOT_ITEM_SQL,
                snapshotId,
                item.merchantProductId(),
                item.merchantVariantId(),
                item.productName(),
                item.productUrl(),
                item.selectedOptions(),
                item.quantity(),
                item.unitPriceAmount(),
                item.discountAmount(),
                item.lineTotalAmount(),
                item.currency());
    }

    private void insertFulfillmentOption(long snapshotId, CheckoutFulfillmentOption option) {
        jdbcTemplate.update(
                INSERT_FULFILLMENT_OPTION_SQL,
                snapshotId,
                option.fulfillmentGroupKey(),
                option.shippingOptionId(),
                option.shippingOptionName(),
                option.deliveryMethod(),
                option.shippingFeeAmount(),
                toTimestamp(option.estimatedDeliveryAt()));
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static byte[] toBytes(EncryptedPii value) {
        return value == null ? null : value.toByteArray();
    }
}
