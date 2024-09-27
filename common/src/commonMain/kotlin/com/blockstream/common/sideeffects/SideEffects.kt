package com.blockstream.common.sideeffects

import com.blockstream.common.data.DenominatedValue
import com.blockstream.common.data.ErrorReport
import com.blockstream.common.data.GreenWallet
import com.blockstream.common.data.LogoutReason
import com.blockstream.common.data.Redact
import com.blockstream.common.data.TwoFactorResolverData
import com.blockstream.common.events.Event
import com.blockstream.common.gdk.data.Account
import com.blockstream.common.gdk.data.AccountAsset
import com.blockstream.common.gdk.data.Device
import com.blockstream.common.gdk.data.ProcessedTransactionDetails
import com.blockstream.common.gdk.params.CreateTransactionParams
import com.blockstream.common.navigation.NavigateDestination
import com.blockstream.common.navigation.PopTo
import com.blockstream.common.utils.StringHolder
import kotlinx.coroutines.CompletableDeferred
import okio.Path
import org.jetbrains.compose.resources.DrawableResource


class SideEffects : SideEffect {
    open class SideEffectEvent(override val event: Event) : SideEffectWithEvent
    data class OpenBrowser(val url: String, val openSystemBrowser: Boolean = false) : SideEffect
    data class OpenMenu(val id: Int = 0) : SideEffect
    data class OpenDialog(val id: Int = 0) : SideEffect
    data class Snackbar(val text: StringHolder) : SideEffect
    data class ErrorSnackbar(val error: Throwable, val errorReport: ErrorReport? = null) :
        SideEffect
    data class Dialog(val title: StringHolder? = null, val message: StringHolder, val icon: DrawableResource? = null) : SideEffect
    data class ErrorDialog(val error: Throwable, val errorReport: ErrorReport? = null) : SideEffect
    data class OpenDenomination(val denominatedValue: DenominatedValue): SideEffect
    data class OpenFeeBottomSheet(
        val greenWallet: GreenWallet,
        val accountAsset: AccountAsset?,
        val params: CreateTransactionParams?,
        val useBreezFees: Boolean = false
    ) : SideEffect
    data class Success(val data: Any? = null) : SideEffect
    data class Mnemonic(val mnemonic: String) : SideEffect, Redact
    data class Navigate(val data: Any? = null) : SideEffect
    data class NavigateTo(val destination: NavigateDestination) : SideEffect
    data class NavigateBack(
        val title: StringHolder? = null,
        val message: StringHolder? = null,
        val error: Throwable? = null,
        val errorReport: ErrorReport? = null,
    ) : SideEffect
    data class NavigateToRoot(val popTo: PopTo? = null) : SideEffect
    data class TransactionSent(val data: ProcessedTransactionDetails) : SideEffect
    data class Logout constructor(val reason: LogoutReason) : SideEffect
    object WalletDelete : SideEffect
    data class CopyToClipboard(val value: String, val message: String? = null, val label: String? = null, val isSensitive: Boolean = false) : SideEffect
    data class AccountArchived(val account: Account) : SideEffect
    data class AccountUnarchived(val account: Account) : SideEffect
    data class AccountCreated(val accountAsset: AccountAsset): SideEffect
    data class UrlWarning(val urls: List<String>): SideEffect
    object TorWarning: SideEffect
    object AppReview: SideEffect
    object DeviceRequestPassphrase: SideEffect
    object DeviceRequestPin: SideEffect
    class DeviceInteraction(
        val device: Device,
        val message: String?,
        val isMasterBlindingKeyRequest: Boolean,
        val completable: CompletableDeferred<Boolean>?
    ) : SideEffect
    object Dismiss : SideEffect
    data class Share(val text: String? = null) : SideEffect
    data class ShareFile(val path: Path) : SideEffect
    data class TwoFactorResolver(val data: TwoFactorResolverData) : SideEffect
    object OpenDenominationExchangeRate : SideEffect
    object LightningShortcut : SideEffect
    data class AskRemoveLightningShortcut(val wallet: GreenWallet) : SideEffect
    object EnableBluetooth: SideEffect
    object EnableLocationService: SideEffect
    object AskForBluetoothPermissions: SideEffect

    object SelectEnvironment: SideEffect
    object BleRequireRebonding: SideEffect
}