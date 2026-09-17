package com.copenhagen.core.checkout;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.copenhagen.core.common.merchant.MerchantRestClients;
import com.copenhagen.core.product.MerchantsProperties;

/**
 * merchant별 Checkout 클라이언트. {@code services.merchants} 목록을 Catalog(순서 24)와 같이 쓴다 —
 * 같은 merchant의 다른 capability라 설정을 따로 두지 않는다.
 */
@Configuration
public class CheckoutConfiguration {

    @Bean
    public Map<String, MerchantCheckoutClient> merchantCheckoutClients(MerchantsProperties properties) {
        Map<String, MerchantCheckoutClient> clients = new LinkedHashMap<>();
        for (MerchantsProperties.MerchantConfig config : properties.merchants()) {
            clients.put(config.id(),
                    new MerchantCheckoutClient(config.id(), MerchantRestClients.create(config)));
        }
        return Map.copyOf(clients);
    }
}
