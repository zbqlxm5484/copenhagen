package com.copenhagen.wallet.features

import androidx.compose.runtime.*
import com.copenhagen.wallet.data.*
import kotlinx.serialization.json.*

/** Core의 미확정 필드 선택은 Models.kt와 이 파일에만 모아 둔다. */
class Account(private val api: Api) {
    var addresses by mutableStateOf<List<Address>?>(null); private set
    var addressId: String? = null
    var form by mutableStateOf(AddressForm())
    var makeDefault by mutableStateOf(false)
    var orders by mutableStateOf<Items<Order>?>(null); private set
    var order by mutableStateOf<Order?>(null); private set
    var orderStatus by mutableStateOf("")
    var profile by mutableStateOf<Profile?>(null); private set
    suspend fun loadAddresses() { addresses = api.core("GET", "/addresses").read<Items<Address>>().items }
    fun newAddress() {
        require(addresses.orEmpty().size < 20) { "배송지는 20개까지 등록할 수 있어요." }
        addressId = null; form = AddressForm(); makeDefault = false
    }
    suspend fun editAddress(id: String) {
        val value = api.core("GET", "/addresses/$id").read<Address>()
        addressId = value.id
        form = AddressForm(value.recipient, value.phone, value.postalCode, value.address, value.detail, value.memo)
        makeDefault = value.isDefault
    }
    suspend fun saveAddress() {
        require(form.valid) { "배송지 정보를 확인해 주세요." }
        val id = addressId
        if (id == null) {
            val response = api.core("POST", "/addresses", json.encodeToJsonElement(form)).jsonObject
            addressId = response.getValue("addressId").jsonPrimitive.content
        } else api.core("PUT", "/addresses/$id", json.encodeToJsonElement(form))
        if (makeDefault) api.core("PATCH", "/addresses/$addressId/default")
        loadAddresses()
    }
    suspend fun defaultAddress(id: String) { api.core("PATCH", "/addresses/$id/default"); loadAddresses() }
    suspend fun deleteAddress() { api.core("DELETE", "/addresses/${requireNotNull(addressId)}"); loadAddresses() }
    suspend fun loadOrders(more: Boolean = false) {
        val previous = orders
        if (!more) orders = null
        if (more && previous?.nextCursor == null) return
        val response = api.core("GET", "/orders", query = buildMap {
            put("size", "20")
            if (more) put("cursor", previous!!.nextCursor!!)
            if (orderStatus.isNotBlank()) put("status", orderStatus)
        }).read<Items<Order>>()
        orders = if (more) response.copy(items = (previous!!.items + response.items).distinctBy { it.id }) else response
    }
    suspend fun openOrder(id: String) { order = null; order = api.core("GET", "/orders/$id").read<Order>() }
    suspend fun loadProfile() { profile = api.core("GET", "/users/me").read<Profile>() }
    fun clear() {
        addresses = null; addressId = null; form = AddressForm(); makeDefault = false
        orders = null; order = null; profile = null
    }
}
