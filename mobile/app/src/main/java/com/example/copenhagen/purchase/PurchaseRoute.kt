package com.example.copenhagen.purchase

import java.net.URI
import java.util.UUID

data class PurchaseRoute(val kind: String, val id: String) {
    val persisted: String get() = "$kind/$id"
    companion object {
        fun parse(value: String?): PurchaseRoute? = runCatching {
            val pieces = value.orEmpty().split('/')
            require(pieces.size == 2 && pieces[0] in setOf("purchase", "approval"))
            val id = UUID.fromString(pieces[1]).toString()
            require(id == pieces[1])
            PurchaseRoute(pieces[0], id)
        }.getOrNull()
        fun link(value: String?, expectedHost: String, pathPrefix: String): PurchaseRoute? = runCatching {
            val uri = URI(value)
            require(uri.scheme == "https" && uri.host == expectedHost && uri.port == -1
                && uri.userInfo == null && uri.query == null && uri.fragment == null)
            require(uri.path.startsWith(pathPrefix))
            parse("purchase/" + uri.path.removePrefix(pathPrefix))
        }.getOrNull()
    }
}
