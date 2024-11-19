package com.blockstream.compose.screens.jade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blockstream_green.common.generated.resources.Res
import blockstream_green.common.generated.resources.id_a_fully_airgapped_workflow_no
import blockstream_green.common.generated.resources.id_keep_your_keys_encrypted_on
import blockstream_green.common.generated.resources.id_not_vulnerable_to_bruteforce
import blockstream_green.common.generated.resources.id_qr_pin_unlock
import blockstream_green.common.generated.resources.id_set_your_pin_via_qr
import blockstream_green.common.generated.resources.id_start_scan_qr_on_jade_and
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import com.blockstream.common.devices.DeviceBrand
import com.blockstream.common.models.GreenViewModel
import com.blockstream.common.models.SimpleGreenViewModel
import com.blockstream.common.models.jade.JadeQrOperation
import com.blockstream.common.navigation.NavigateDestinations
import com.blockstream.compose.components.GreenButton
import com.blockstream.compose.components.GreenButtonSize
import com.blockstream.compose.components.GreenColumn
import com.blockstream.compose.components.GreenRow
import com.blockstream.compose.theme.bodyLarge
import com.blockstream.compose.theme.headlineMedium
import com.blockstream.compose.theme.titleSmall
import com.blockstream.compose.theme.whiteHigh
import com.blockstream.compose.theme.whiteMedium
import com.blockstream.compose.utils.AppBar
import com.blockstream.compose.utils.HandleSideEffect
import org.jetbrains.compose.resources.stringResource
import org.koin.core.parameter.parametersOf


object JadePinUnlockScreen : Screen {
    @Composable
    override fun Content() {
        val viewModel = koinScreenModel<SimpleGreenViewModel> {
            parametersOf(null, null, "JadePinUnlock")
        }

        val navData by viewModel.navData.collectAsStateWithLifecycle()

        AppBar(navData)

        JadePinUnlockScreen(viewModel = viewModel)
    }
}

@Composable
fun JadePinUnlockScreen(
    viewModel: GreenViewModel
) {

    HandleSideEffect(viewModel = viewModel)

    JadeQRScreen.getResultPinUnlock {
        viewModel.postEvent(NavigateDestinations.ImportPubKey(deviceBrand = DeviceBrand.Blockstream))
    }

    val texts = listOf(
        Res.string.id_a_fully_airgapped_workflow_no,
        Res.string.id_keep_your_keys_encrypted_on,
        Res.string.id_not_vulnerable_to_bruteforce
    )

    GreenColumn(verticalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxSize()) {
        GreenColumn(padding = 32, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(Res.string.id_set_your_pin_via_qr), style = headlineMedium, textAlign = TextAlign.Center)
            Text(stringResource(Res.string.id_start_scan_qr_on_jade_and), style = titleSmall, color = whiteMedium, textAlign = TextAlign.Center)
        }

        GreenColumn {

            texts.forEachIndexed { index, stringResource ->
                GreenRow(space = 8, verticalAlignment = Alignment.Top) {
                    Text("${index + 1}".padStart(2, '0'), style = bodyLarge, color = whiteMedium)
                    Text(stringResource(stringResource), style = bodyLarge, color = whiteHigh)
                }
            }
        }

        GreenButton(stringResource(Res.string.id_qr_pin_unlock), size = GreenButtonSize.BIG, modifier = Modifier.fillMaxWidth(), onClick = {
            viewModel.postEvent(NavigateDestinations.JadeQR(operation = JadeQrOperation.PinUnlock, deviceBrand = DeviceBrand.Blockstream))
        })
    }
}