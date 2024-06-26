package com.blockstream.compose.sheets

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.koin.getScreenModel
import com.arkivanov.essenty.parcelable.Parcelable
import com.arkivanov.essenty.parcelable.Parcelize
import com.blockstream.common.data.GreenWallet
import com.blockstream.common.extensions.previewAccount
import com.blockstream.common.extensions.previewWallet
import com.blockstream.common.gdk.data.Account
import com.blockstream.common.models.GreenViewModel
import com.blockstream.common.models.login.Bip39PassphraseViewModel
import com.blockstream.compose.GreenPreview
import com.blockstream.compose.components.GreenAccount
import com.blockstream.compose.components.GreenBottomSheet
import com.blockstream.compose.components.GreenColumn
import com.blockstream.compose.navigation.resultKey
import com.blockstream.compose.navigation.setNavigationResult
import org.koin.core.parameter.parametersOf

@Parcelize
data class AccountsBottomSheet(
    val greenWallet: GreenWallet,
    val accounts: List<Account>
) : BottomScreen(), Parcelable {
    @Composable
    override fun Content() {
        val viewModel = getScreenModel<GreenViewModel> {
            parametersOf(greenWallet)
        }

        AccountsBottomSheet(
            viewModel = viewModel,
            accounts = accounts,
            onDismissRequest = onDismissRequest()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsBottomSheet(
    viewModel: GreenViewModel,
    accounts: List<Account>,
    onDismissRequest: () -> Unit,
) {
    GreenBottomSheet(
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        ),
        onDismissRequest = onDismissRequest
    ) {

        GreenColumn(
            padding = 0, space = 8, modifier = Modifier
                .padding(top = 16.dp)
                .verticalScroll(
                    rememberScrollState()
                )
        ) {
            accounts.forEach { account ->
                GreenAccount(account = account, session = viewModel.sessionOrNull) {
                    setNavigationResult(AccountsBottomSheet::class.resultKey, account)
                    onDismissRequest()
                }
            }
        }

    }
}

@Composable
@Preview
fun AccountsBottomSheetPreview() {
    GreenPreview {
        AccountsBottomSheet(
            viewModel = GreenViewModel(previewWallet()),
            accounts = listOf(previewAccount()),
            onDismissRequest = { }
        )
    }
}