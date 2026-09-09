package com.copenhagen.merchantmock.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Map;

import com.copenhagen.merchantmock.support.MerchantProperties;
import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 서명 키 로딩 테스트.
 *
 * <p>개인키를 테스트 리소스로 커밋하지 않는다. 필요한 키 쌍은 테스트가 실행될 때마다 새로 만든다.
 */
class SigningKeyProviderTest {

    private static final String KID = "merchant-a-es256-1";

    @Test
    void 공개키만_있으면_기동하고_서명은_못한다() {
        SigningKeyProvider provider = new SigningKeyProvider(MerchantPropertiesFixture.defaults());

        assertFalse(provider.canSign());
        assertTrue(provider.publicJwk().isPresent());
        assertThrows(IllegalStateException.class, provider::requireSigningKey);
    }

    @Test
    void 공개키가_비어_있으면_keys는_빈값이다() {
        MerchantProperties properties = MerchantPropertiesFixture.withSigning(
                new MerchantProperties.Signing(KID, "ES256", "", "", ""));

        SigningKeyProvider provider = new SigningKeyProvider(properties);

        assertTrue(provider.publicJwk().isEmpty());
        assertFalse(provider.canSign());
    }

    @Test
    void 공개키_JWK에_kid와_곡선이_실린다() {
        SigningKeyProvider provider = new SigningKeyProvider(MerchantPropertiesFixture.defaults());

        Map<String, Object> jwk = provider.publicJwk().orElseThrow();

        assertEquals("EC", jwk.get("kty"));
        assertEquals("P-256", jwk.get("crv"));
        assertEquals(KID, jwk.get("kid"));
        assertFalse(jwk.containsKey("d"), "공개키에 개인키 성분이 실리면 안 된다");
    }

    @Test
    void 짝이_맞는_개인키를_읽으면_서명할_수_있다() throws Exception {
        KeyPair pair = generateP256();
        MerchantProperties properties = MerchantPropertiesFixture.withSigning(
                new MerchantProperties.Signing(KID, "ES256", "", toPkcs8Pem(pair), publicJwkOf(pair)));

        SigningKeyProvider provider = new SigningKeyProvider(properties);

        assertTrue(provider.canSign());
        assertEquals(KID, provider.requireSigningKey().getKeyID());
    }

    @Test
    void 짝이_안_맞는_개인키는_기동에서_잡는다() throws Exception {
        String otherPem = toPkcs8Pem(generateP256());
        String jwk = publicJwkOf(generateP256());
        MerchantProperties properties = MerchantPropertiesFixture.withSigning(
                new MerchantProperties.Signing(KID, "ES256", "", otherPem, jwk));

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> new SigningKeyProvider(properties));

        assertTrue(thrown.getMessage().contains("같은 쌍이 아니다"), thrown.getMessage());
    }

    @Test
    void 개인키만_있으면_기동에서_잡는다() throws Exception {
        MerchantProperties properties = MerchantPropertiesFixture.withSigning(
                new MerchantProperties.Signing(KID, "ES256", "", toPkcs8Pem(generateP256()), ""));

        assertThrows(IllegalStateException.class, () -> new SigningKeyProvider(properties));
    }

    @Test
    void 없는_경로를_주면_기동에서_잡는다() {
        MerchantProperties properties = MerchantPropertiesFixture.withSigning(
                new MerchantProperties.Signing(
                        KID, "ES256", "/does/not/exist.pem", "", MerchantPropertiesFixture.PUBLIC_JWK));

        assertThrows(IllegalStateException.class, () -> new SigningKeyProvider(properties));
    }

    private static KeyPair generateP256() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String toPkcs8Pem(KeyPair pair) {
        ECPrivateKey privateKey = (ECPrivateKey) pair.getPrivate();
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(privateKey.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    private static String publicJwkOf(KeyPair pair) {
        ECKey key = new ECKey.Builder(Curve.P_256, (ECPublicKey) pair.getPublic()).keyID(KID).build();
        return key.toJSONString();
    }
}
