package com.copenhagen.merchantmock.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.ParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.copenhagen.merchantmock.support.MerchantProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ES256 서명 키를 로딩하고 공개키를 Profile 에 넘긴다.
 *
 * <p>공개키와 개인키를 갈라서 다룬다. 공개키는 노출돼도 되므로 application.yml 에 두고,
 * 개인키는 환경변수로 주입한 파일 경로나 PEM 문자열에서 읽는다(설계서 18.5).
 *
 * <p>개인키가 없어도 기동한다. Profile 은 공개키만 있으면 되고 서명은 Jira 175 에서 붙는다.
 * 서명을 실제로 시도하는 시점에만 실패한다.
 */
@Component
public class SigningKeyProvider {

    private static final Logger LOG = LoggerFactory.getLogger(SigningKeyProvider.class);
    private static final String PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_FOOTER = "-----END PRIVATE KEY-----";
    private static final String PAIR_CHECK_PAYLOAD = "merchant-mock-keypair-check";

    private final ECKey publicJwk;
    private final ECKey signingKey;

    public SigningKeyProvider(MerchantProperties properties) {
        MerchantProperties.Signing signing = properties.signing();
        this.publicJwk = parsePublicJwk(signing);
        this.signingKey = buildSigningKey(signing, this.publicJwk);
    }

    /** Profile 의 keys[] 에 실을 공개키. 설정이 비어 있으면 빈 값을 준다. */
    public Optional<Map<String, Object>> publicJwk() {
        if (publicJwk == null) {
            return Optional.empty();
        }
        return Optional.of(new LinkedHashMap<>(publicJwk.toPublicJWK().toJSONObject()));
    }

    /** 서명에 쓸 개인키. 없으면 던진다. 호출 지점은 Jira 175 의 Merchant Authorization 발급이다. */
    public ECKey requireSigningKey() {
        if (signingKey == null) {
            throw new IllegalStateException(
                    "서명 개인키가 설정되지 않았다. MERCHANT_SIGNING_PRIVATE_KEY_LOCATION 또는 "
                            + "MERCHANT_SIGNING_PRIVATE_KEY_PEM 을 주입해야 한다");
        }
        return signingKey;
    }

    public boolean canSign() {
        return signingKey != null;
    }

    private static ECKey parsePublicJwk(MerchantProperties.Signing signing) {
        String json = signing.publicKeyJwk();
        if (json == null || json.isBlank()) {
            LOG.warn("공개키 JWK 가 비어 있다. Profile 의 keys[] 가 빈 배열로 나간다");
            return null;
        }
        try {
            ECKey parsed = ECKey.parse(json);
            if (parsed.getKeyID() == null) {
                parsed = new ECKey.Builder(parsed).keyID(signing.kid()).build();
            }
            LOG.info("공개키 JWK 로딩 완료. kid={} crv={}", parsed.getKeyID(), parsed.getCurve());
            return parsed;
        } catch (ParseException exception) {
            throw new IllegalStateException("merchant.signing.public-key-jwk 를 EC JWK 로 읽을 수 없다", exception);
        }
    }

    private static ECKey buildSigningKey(MerchantProperties.Signing signing, ECKey publicJwk) {
        String pem = readPrivateKeyPem(signing);
        if (pem == null) {
            LOG.info("서명 개인키 미설정. Profile 은 정상 동작하고 서명이 필요한 기능만 실패한다");
            return null;
        }
        if (publicJwk == null) {
            throw new IllegalStateException("개인키만 설정됐다. merchant.signing.public-key-jwk 도 함께 채워야 한다");
        }
        ECPrivateKey privateKey = decodePkcs8(pem);
        ECKey key = new ECKey.Builder(publicJwk).privateKey(privateKey).build();
        verifyKeyPair(key);
        LOG.info("서명 개인키 로딩 완료. kid={}", key.getKeyID());
        return key;
    }

    private static String readPrivateKeyPem(MerchantProperties.Signing signing) {
        String inline = signing.privateKeyPem();
        if (inline != null && !inline.isBlank()) {
            return inline;
        }
        String location = signing.privateKeyLocation();
        if (location == null || location.isBlank()) {
            return null;
        }
        Path path = Path.of(location);
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("서명 개인키 파일을 읽을 수 없다: " + location);
        }
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("서명 개인키 파일 읽기에 실패했다: " + location, exception);
        }
    }

    /** PKCS#8 PEM 만 받는다. openssl pkcs8 -topk8 -nocrypt 로 변환한 파일이다. */
    private static ECPrivateKey decodePkcs8(String pem) {
        String body = pem.replace(PEM_HEADER, "").replace(PEM_FOOTER, "").replaceAll("\\s", "");
        if (body.isEmpty()) {
            throw new IllegalStateException("개인키 PEM 이 비어 있다. PKCS#8 형식인지 확인한다");
        }
        try {
            byte[] der = Base64.getDecoder().decode(body);
            KeyFactory factory = KeyFactory.getInstance("EC");
            return (ECPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException | ClassCastException exception) {
            // base64 깨짐, PKCS#8 이 아닌 DER, EC 가 아닌 키 순서로 각각 걸린다.
            throw new IllegalStateException(
                    "개인키를 PKCS#8 EC 키로 읽을 수 없다. BEGIN PRIVATE KEY 헤더가 필요하다", exception);
        }
    }

    /** 공개키와 개인키가 같은 쌍인지 서명 왕복으로 확인한다. 틀린 키를 붙이면 기동에서 잡는다. */
    private static void verifyKeyPair(ECKey key) {
        try {
            JWSObject probe = new JWSObject(
                    new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(),
                    new Payload(PAIR_CHECK_PAYLOAD));
            probe.sign(new ECDSASigner(key.toECPrivateKey(), Curve.P_256));
            if (!probe.verify(new ECDSAVerifier(key.toECPublicKey()))) {
                throw new IllegalStateException("공개키와 개인키가 같은 쌍이 아니다");
            }
        } catch (JOSEException exception) {
            throw new IllegalStateException("서명 키 쌍 검증에 실패했다", exception);
        }
    }
}
