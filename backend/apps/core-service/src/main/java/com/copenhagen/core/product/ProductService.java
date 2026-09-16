package com.copenhagen.core.product;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

@Service
public class ProductService {

    private static final String DEFAULT_CONNECTION_REF = "default";
    private static final String KRW = "KRW";

    private final Map<String, MerchantCatalogClient> clients;
    private final Clock clock;

    public ProductService(Map<String, MerchantCatalogClient> merchantCatalogClients, Clock coreClock) {
        this.clients = merchantCatalogClients;
        this.clock = coreClock;
    }

    public SearchEnvelope search(UUID principal, SearchQuery query) {
        List<MerchantCatalogClient> targets = query.merchantId().isBlank()
                ? List.copyOf(clients.values())
                : clients.containsKey(query.merchantId())
                        ? List.of(clients.get(query.merchantId()))
                        : List.of();

        List<CatalogProduct> products = new ArrayList<>();
        List<String> failedMerchants = new ArrayList<>();
        for (MerchantCatalogClient client : targets) {
            try {
                for (MerchantCatalogClient.CatalogItem item
                        : client.search(query.query(), query.maxPrice(), KRW)) {
                    if (!KRW.equals(item.price().currency())) {
                        continue;
                    }
                    if (query.minPrice() != null && item.price().amount() < query.minPrice()) {
                        continue;
                    }
                    if (!query.category().isBlank() || !query.brand().isBlank()) {
                        continue;
                    }
                    products.add(new CatalogProduct(
                            item.productId(), item.title(), "", "", "",
                            List.of(new Offer(client.merchantId(), client.merchantName(),
                                    item.price().amount(), KRW, item.availability()))));
                }
            } catch (CoreException exception) {
                failedMerchants.add(client.merchantId());
            }
        }

        sort(products, query.sort());
        int total = products.size();
        List<CatalogProduct> page = paginate(products, query.page(), query.pageSize());
        boolean hasNext = (long) query.page() * query.pageSize() < total;
        String status = !failedMerchants.isEmpty() ? "PARTIAL" : page.isEmpty() ? "EMPTY" : "OK";

        SearchData data = new SearchData(
                "catalog.search", "LIVE", status, query, page,
                List.of(), merchantRefs(targets), failedMerchants, total, hasNext,
                clock.instant().toString());
        return new SearchEnvelope(principal.toString(), DEFAULT_CONNECTION_REF, data);
    }

    public DetailEnvelope getDetail(UUID principal, String productId, String merchantId, String optionId) {
        MerchantCatalogClient client = clients.get(merchantId);
        if (client == null) {
            throw new CoreException(
                    CoreErrorCode.INVALID_REQUEST, "등록되지 않은 merchantId입니다.", null, "CHECK_REQUEST");
        }

        MerchantCatalogClient.CatalogProductDetail detail;
        try {
            detail = client.product(productId);
        } catch (CoreException exception) {
            if (exception.errorCode() != CoreErrorCode.PRODUCT_NOT_FOUND) {
                throw exception;
            }
            DetailData notFound = new DetailData(
                    "catalog.detail", "LIVE", "NOT_FOUND",
                    requestEcho(productId, merchantId, optionId), null, clock.instant().toString());
            return new DetailEnvelope(principal.toString(), DEFAULT_CONNECTION_REF, notFound);
        }

        List<Variant> variants = toVariants(detail);
        Offer baseOffer = new Offer(client.merchantId(), client.merchantName(),
                detail.price().amount(), KRW, detail.availability());
        Selection selection = resolveSelection(variants, baseOffer, optionId);
        if ("VALID".equals(selection.status())) {
            variants = withResolvedVariant(variants, selection.selectedOptionId(), selection.offer());
        }

        ProductDetail product = new ProductDetail(
                detail.productId(), detail.title(), "", "", "", detail.description(),
                List.of(), selection.offer(), variants,
                selection.optionsComplete(), !variants.isEmpty(),
                selection.status(), selection.selectedOptionId(), null,
                new Shipping("UNKNOWN", null, ""));
        DetailData data = new DetailData(
                "catalog.detail", "LIVE", "FOUND",
                requestEcho(productId, merchantId, optionId), product, clock.instant().toString());
        return new DetailEnvelope(principal.toString(), DEFAULT_CONNECTION_REF, data);
    }

    /** 검증기가 "선택된 variant의 price·availability는 offer와 같아야 한다"고 요구해 선택된 항목만 갱신한다. */
    private List<Variant> withResolvedVariant(List<Variant> variants, String selectedOptionId, Offer offer) {
        List<Variant> resolved = new ArrayList<>(variants.size());
        for (Variant variant : variants) {
            if (variant.optionId().equals(selectedOptionId)) {
                resolved.add(new Variant(
                        variant.optionId(), variant.label(), offer.price(), offer.availability(), null));
            } else {
                resolved.add(variant);
            }
        }
        return List.copyOf(resolved);
    }

    private List<Variant> toVariants(MerchantCatalogClient.CatalogProductDetail detail) {
        List<Variant> variants = new ArrayList<>();
        for (MerchantCatalogClient.CatalogOption option : detail.options()) {
            for (MerchantCatalogClient.CatalogOptionValue value : option.values()) {
                variants.add(new Variant(value.optionValueId(), value.label(), null, "UNKNOWN", null));
            }
        }
        return List.copyOf(variants);
    }

    /** {@code ProductDetailService.get()}의 검증 로직을 그대로 만족하도록 상태를 정한다 — 설계서 §3. */
    private Selection resolveSelection(List<Variant> variants, Offer baseOffer, String optionId) {
        if (variants.isEmpty()) {
            if (optionId.isBlank()) {
                return new Selection("NONE", null, baseOffer, true);
            }
            return new Selection("INVALID", null, priceless(baseOffer), true);
        }
        if (optionId.isBlank()) {
            return new Selection("REQUIRED", null, priceless(baseOffer), false);
        }
        for (Variant variant : variants) {
            if (variant.optionId().equals(optionId)) {
                return new Selection("VALID", optionId, baseOffer, true);
            }
        }
        return new Selection("INVALID", null, priceless(baseOffer), false);
    }

    private Offer priceless(Offer offer) {
        return new Offer(offer.merchantId(), offer.merchantName(), null, offer.currency(), offer.availability());
    }

    private DetailRequestEcho requestEcho(String productId, String merchantId, String optionId) {
        return new DetailRequestEcho(productId, merchantId, optionId.isBlank() ? null : optionId);
    }

    private void sort(List<CatalogProduct> products, String sort) {
        Comparator<CatalogProduct> byPrice = Comparator.comparing(
                product -> product.offers().get(0).price(), Comparator.nullsLast(Comparator.naturalOrder()));
        if ("PRICE_ASC".equals(sort)) {
            products.sort(byPrice);
        } else if ("PRICE_DESC".equals(sort)) {
            products.sort(byPrice.reversed());
        }
    }

    private List<CatalogProduct> paginate(List<CatalogProduct> products, int page, int pageSize) {
        int fromIndex = Math.min((page - 1) * pageSize, products.size());
        int toIndex = Math.min(fromIndex + pageSize, products.size());
        return List.copyOf(products.subList(fromIndex, toIndex));
    }

    private List<MerchantRef> merchantRefs(List<MerchantCatalogClient> targets) {
        return targets.stream().map(client -> new MerchantRef(client.merchantId(), client.merchantName())).toList();
    }

    private record Selection(String status, String selectedOptionId, Offer offer, boolean optionsComplete) {
    }
}
