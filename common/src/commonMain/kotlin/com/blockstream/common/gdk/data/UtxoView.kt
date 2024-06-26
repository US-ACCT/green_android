package com.blockstream.common.gdk.data

import com.arkivanov.essenty.parcelable.Parcelable
import com.arkivanov.essenty.parcelable.Parcelize
import com.blockstream.common.gdk.GreenJson
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class UtxoView constructor(
    val address: String? = null,
    val isBlinded: Boolean? = null,
    val isConfidential: Boolean? = null,
    val assetId: String? = null,
    val satoshi: Long? = null,
    val isChange: Boolean = false,
): GreenJson<UtxoView>(), Parcelable {
    override fun kSerializer() = serializer()

    companion object{
        fun fromOutput(output: Output): UtxoView {
            return UtxoView(
                address = output.domain ?: output.address,
                assetId = output.assetId,
                satoshi = -output.satoshi,
                isChange = output.isChange == true,
            )
        }
    }
}