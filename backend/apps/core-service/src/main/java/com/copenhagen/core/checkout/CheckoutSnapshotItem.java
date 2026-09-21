package com.copenhagen.core.checkout;

/**
 * {@code checkout_snapshot_items} 한 행.
 *
 * <p>수량·단가는 merchant가 확정한 값이고, 상품 url과 선택 옵션 상세는 장바구니에서 온다 — merchant
 * Checkout 응답에는 그 둘이 없다.
 */
public record CheckoutSnapshotItem(
        String merchantProductId,
        String merchantVariantId,
        String productName,
        String productUrl,
        String selectedOptions,
        int quantity,
        long unitPriceAmount,
        long discountAmount,
        long lineTotalAmount,
        String currency,
        java.util.UUID cartItemPublicId) {
    public CheckoutSnapshotItem(String merchantProductId, String merchantVariantId, String productName,
            String productUrl, String selectedOptions, int quantity, long unitPriceAmount,
            long discountAmount, long lineTotalAmount, String currency) {
        this(merchantProductId, merchantVariantId, productName, productUrl, selectedOptions, quantity,
                unitPriceAmount, discountAmount, lineTotalAmount, currency, null);
    }
}
