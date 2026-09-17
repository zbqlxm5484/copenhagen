package com.copenhagen.core.checkout;

import java.sql.Timestamp;
import java.time.Instant;
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
    public void insertAll(List<MerchantCheckoutRecord> records, Instant now) {
        for (MerchantCheckoutRecord record : records) {
            long checkoutId = insertCheckout(record.checkout(), now);
            long snapshotId = insertSnapshot(record.snapshot(), checkoutId, now);
            for (CheckoutSnapshotItem item : record.items()) {
                insertItem(snapshotId, item);
            }
            for (CheckoutFulfillmentOption option : record.fulfillmentOptions()) {
                insertFulfillmentOption(snapshotId, option);
            }
        }
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
