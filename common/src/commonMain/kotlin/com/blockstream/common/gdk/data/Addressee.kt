package com.blockstream.common.gdk.data

import com.blockstream.common.gdk.GreenJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Addressee constructor(
    @SerialName("address") val address: String,
    @SerialName("asset_id") val assetId: String? = null,
    @SerialName("satoshi") val satoshi: Long? = null,
    @SerialName("bip21-params") val bip21Params: Bip21Params? = null,
    @SerialName("has_locked_amount") val hasLockedAmount: Boolean? = null,
    @SerialName("min_amount") val minAmount: Long? = null,
    @SerialName("max_amount") val maxAmount: Long? = null,
    @SerialName("domain") val domain: String? = null,
    @SerialName("metadata") val metadata: List<List<String>>? = null,
) : GreenJson<Addressee>() {
    override fun kSerializer() = serializer()
}