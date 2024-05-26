package com.blockstream.compose.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.koin.koinScreenModel
import com.arkivanov.essenty.parcelable.Parcelable
import com.arkivanov.essenty.parcelable.Parcelize
import com.blockstream.common.data.GreenWallet
import com.blockstream.common.gdk.data.Transaction
import com.blockstream.common.models.sheets.TransactionDetailsViewModel
import com.blockstream.common.models.sheets.TransactionDetailsViewModelAbstract
import com.blockstream.common.models.sheets.TransactionDetailsViewModelPreview
import com.blockstream.compose.GreenPreview
import com.blockstream.compose.R
import com.blockstream.compose.components.GreenBottomSheet
import com.blockstream.compose.components.GreenColumn
import com.blockstream.compose.components.GreenRow
import com.blockstream.compose.theme.bodyLarge
import com.blockstream.compose.theme.labelMedium
import com.blockstream.compose.theme.whiteHigh
import com.blockstream.compose.theme.whiteMedium
import com.blockstream.compose.utils.copyToClipboard
import com.blockstream.compose.utils.noRippleClickable
import com.blockstream.compose.utils.stringResourceId
import com.blockstream.compose.views.DataListItem
import org.koin.core.parameter.parametersOf

@Parcelize
data class TransactionDetailsBottomSheet(
    val greenWallet: GreenWallet,
    val transaction: Transaction
) : BottomScreen(), Parcelable {
    @Composable
    override fun Content() {
        val viewModel = koinScreenModel<TransactionDetailsViewModel> {
            parametersOf(transaction, greenWallet)
        }

        TransactionDetailsBottomSheet(
            viewModel = viewModel,
            onDismissRequest = onDismissRequest()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailsBottomSheet(
    viewModel: TransactionDetailsViewModelAbstract,
    onDismissRequest: () -> Unit,
) {
    GreenBottomSheet(
        title = stringResource(id = R.string.id_more_details),
        viewModel = viewModel,
        onDismissRequest = onDismissRequest
    ) {

        val data by viewModel.data.collectAsStateWithLifecycle()

        GreenColumn(
            padding = 0, space = 16, modifier = Modifier
                .padding(top = 16.dp)
                .verticalScroll(
                    rememberScrollState()
                )
        ) {
            data.forEachIndexed { index, pair ->
                DataListItem(
                    title = pair.first,
                    data = pair.second,
                    withDivider = index < data.size - 1
                )
            }
        }
    }
}

@Composable
@Preview
fun TransactionDetailsBottomSheetPreview() {
    GreenPreview {
        TransactionDetailsBottomSheet(
            viewModel = TransactionDetailsViewModelPreview.preview(),
            onDismissRequest = { }
        )
    }
}