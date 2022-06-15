package com.blockstream.green.ui.onboarding

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.blockstream.green.data.Countly
import com.blockstream.green.data.OnboardingOptions
import com.blockstream.green.database.Wallet
import com.blockstream.green.database.WalletRepository
import com.blockstream.green.gdk.SessionManager
import com.blockstream.green.utils.logException
import com.blockstream.green.utils.nameCleanup
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.launch

class WalletNameViewModel @AssistedInject constructor(
    sessionManager: SessionManager,
    walletRepository: WalletRepository,
    countly: Countly,
    @Assisted val onboardingOptions: OnboardingOptions,
    @Assisted restoreWallet: Wallet?
) : OnboardingViewModel(sessionManager, walletRepository, countly, restoreWallet) {
    val walletName = MutableLiveData(restoreWallet?.name ?: "")
    val walletNameHint = MutableLiveData("").also {
        viewModelScope.launch(context = logException(countly)){
            it.value = generateWalletNameSuspend(onboardingOptions.network!!, null)
        }
    }

    fun getName() = walletName.value.nameCleanup()

    @dagger.assisted.AssistedFactory
    interface AssistedFactory {
        fun create(
            onboardingOptions: OnboardingOptions,
            restoreWallet: Wallet?
        ): WalletNameViewModel
    }

    companion object {
        fun provideFactory(
            assistedFactory: AssistedFactory,
            onboardingOptions: OnboardingOptions,
            restoreWallet: Wallet?
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return assistedFactory.create(onboardingOptions, restoreWallet) as T
            }
        }
    }
}