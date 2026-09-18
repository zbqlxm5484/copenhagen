package com.example.copenhagen.purchase

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class BiometricApproval(private val activity: FragmentActivity, private val deviceId: String) {
    private val alias = "copenhagen-approval-" + deviceId
    fun publicKey(): String {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true).build())
            }.generateKeyPair()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(store.getCertificate(alias).publicKey.encoded)
    }
    fun sign(challenge: String, success: (String) -> Unit, failure: (String) -> Unit) {
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val signer = Signature.getInstance("SHA256withECDSA").apply {
                initSign(store.getKey(alias, null) as PrivateKey)
            }
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        try {
                            val signature = result.cryptoObject?.signature ?: error("인증 서명이 없습니다.")
                            signature.update(challenge.toByteArray(Charsets.UTF_8))
                            success(Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()))
                        } catch (_: Exception) { failure("생체 인증 서명을 만들지 못했습니다.") }
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        failure(errString.toString())
                    }
                })
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("결제 승인")
                .setSubtitle("확인한 주문 금액으로 결제를 승인합니다.")
                .setNegativeButtonText("취소").build(), BiometricPrompt.CryptoObject(signer))
        } catch (_: Exception) { failure("이 기기의 생체 인증 정보를 확인해주세요.") }
    }
}
