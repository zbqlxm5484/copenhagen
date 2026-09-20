package com.copenhagen.wallet.features

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.copenhagen.wallet.data.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class Biometrics {
    private val alias = "copenhagen.biometric.p256"
    private var registrationKey = newKey()
    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    fun available(activity: FragmentActivity) = BiometricManager.from(activity)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    fun clear() { keystore().deleteEntry(alias); registrationKey = newKey() }

    suspend fun enroll(activity: FragmentActivity, api: Api, store: Store) {
        prompt(activity)
        val publicKey = keystore().getCertificate(alias)?.publicKey ?: createKey()
        val response = api.pay("POST", "/users/me/biometric-credentials", buildJsonObject {
            put("deviceId", store.deviceId)
            put("publicKey", Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.encoded))
            put("credentialType", "PLATFORM_BIOMETRIC")
        }, registrationKey).read<BiometricCredential>()
        store.credentialId = response.credentialId
    }
    suspend fun sign(activity: FragmentActivity, challenge: Challenge): String {
        check(java.time.Instant.parse(challenge.expiresAt).isAfter(java.time.Instant.now())) { "생체 인증 요청이 만료되었어요." }
        val key = keystore().getKey(alias, null) as? PrivateKey
            ?: throw IllegalStateException("생체 인증을 다시 등록해 주세요.")
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
        val authenticated = requireNotNull(prompt(activity, signature))
        // Base64URL처럼 보이더라도 challenge는 디코드하지 않는다. Pay v1.7의 UTF-8 원문 서명 규약.
        authenticated.update(challenge.challenge.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(authenticated.sign())
    }
    private fun createKey(): PublicKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        else { @Suppress("DEPRECATION") spec.setUserAuthenticationValidityDurationSeconds(-1) }
        return KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply { initialize(spec.build()) }.generateKeyPair().public
    }
    private suspend fun prompt(activity: FragmentActivity, signature: Signature? = null): Signature? {
        check(available(activity)) { "기기에 생체 인증을 등록해 주세요." }
        return suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (!continuation.isActive) return
                    if (signature != null && result.cryptoObject?.signature == null)
                        continuation.resumeWithException(IllegalStateException("생체 인증을 완료하지 못했어요."))
                    else continuation.resume(result.cryptoObject?.signature)
                }
                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("생체 인증을 완료하지 못했어요."))
                }
            })
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            val info = BiometricPrompt.PromptInfo.Builder().setTitle("Copenhagen")
                .setSubtitle("본인 인증").setNegativeButtonText("취소")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).build()
            if (signature == null) prompt.authenticate(info)
            else prompt.authenticate(info, BiometricPrompt.CryptoObject(signature))
        }
    }
}
