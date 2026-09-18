package com.example.copenhagen.purchase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PurchaseRouteTest {
    private val id = "8a977a89-9fa9-45b6-a592-f2c06dd14076"
    @Test fun handoffAndApprovalRemainDifferentRoutes() {
        assertEquals("purchase", PurchaseRoute.parse("purchase/$id")?.kind)
        assertEquals("approval", PurchaseRoute.parse("approval/$id")?.kind)
        assertNull(PurchaseRoute.parse("purchase/../$id"))
        assertNull(PurchaseRoute.parse("approval/not-a-uuid"))
    }
    @Test fun onlyConfiguredHttpsHostAndPathAreAccepted() {
        val prefix = "https://shop.example/app/purchases/"
        assertEquals(id, PurchaseRoute.link(prefix + id, "shop.example", "/app/purchases/")?.id)
        for (url in listOf("http://shop.example/app/purchases/$id", prefix + id + "?admin=1",
            "https://attacker.example/app/purchases/$id", "https://shop.example/approval/$id",
            "https://evil@shop.example/app/purchases/$id")) {
            assertNull(PurchaseRoute.link(url, "shop.example", "/app/purchases/"))
        }
    }
}
