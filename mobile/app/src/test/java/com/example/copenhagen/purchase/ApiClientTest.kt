package com.example.copenhagen.purchase

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ApiClientTest {
    private class Session : ApiSession {
        override var tokens = JSONObject().put("accessToken", "test-access").put("refreshToken", "test-refresh")
        override val deviceId = "test-device"
    }
    @Test fun unauthorizedApprovalRefreshesCoreTokenAndReplaysSameRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"accessToken":"new-access","refreshToken":"new-refresh"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"APPROVED"}"""))
            val session = Session()
            val client = ApiClient(session, server.url("/").toString())
            val body = JSONObject().put("authentication", JSONObject().put("method", "PIN").put("pin", "123456"))
            client.request("/api/pay/v1/payment-approvals/test/approve", "POST", body)
            val first = server.takeRequest()
            val refresh = server.takeRequest()
            val retry = server.takeRequest()
            assertEquals("/api/core/v1/auth/token/refresh", refresh.path)
            assertNull(refresh.getHeader("Authorization"))
            assertEquals("new-refresh", session.tokens.getString("refreshToken"))
            assertEquals("Bearer new-access", retry.getHeader("Authorization"))
            assertEquals("test-device", retry.getHeader("X-Device-Id"))
            assertNotNull(first.getHeader("Idempotency-Key"))
            assertEquals(first.getHeader("Idempotency-Key"), retry.getHeader("Idempotency-Key"))
            assertEquals(first.body.readUtf8(), retry.body.readUtf8())
        }
    }
    @Test fun failedResumeRetainsItsKeyAndDoesNotCreateANewPurchase() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"code":"UNAVAILABLE"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"CREATING_CHECKOUTS"}"""))
            val client = ApiClient(Session(), server.url("/").toString())
            val path = "/api/core/v1/purchase-requests/test/resume"
            val body = JSONObject().put("addressId", "address-test").put("cartVersion", 4)
            try { client.request(path, "POST", body) } catch (_: ApiFailure) { }
            client.request(path, "POST", body)
            val first = server.takeRequest()
            val retry = server.takeRequest()
            assertEquals(path, retry.path)
            assertEquals(first.getHeader("Idempotency-Key"), retry.getHeader("Idempotency-Key"))
            assertEquals(first.body.readUtf8(), retry.body.readUtf8())
        }
    }
    @Test fun pgTokenizationNeverReceivesUserAccessToken() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"provider":"mock-pg","providerTokenRef":"test-reference"}"""))
            val client = ApiClient(Session(), server.url("/").toString())
            client.request("/api/pg/v1/payment-methods/tokenize", "POST", JSONObject(), false)
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }
}
