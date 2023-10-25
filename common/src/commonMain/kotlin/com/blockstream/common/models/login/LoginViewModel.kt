package com.blockstream.common.models.login

import com.blockstream.common.crypto.PlatformCipher
import com.blockstream.common.data.ApplicationSettings
import com.blockstream.common.data.CredentialType
import com.blockstream.common.data.DataState
import com.blockstream.common.data.ErrorReport
import com.blockstream.common.data.GreenWallet
import com.blockstream.common.data.Redact
import com.blockstream.common.data.WatchOnlyCredentials
import com.blockstream.common.data.data
import com.blockstream.common.data.isEmpty
import com.blockstream.common.database.LoginCredentials
import com.blockstream.common.events.Event
import com.blockstream.common.extensions.biometricsPinData
import com.blockstream.common.extensions.biometricsWatchOnlyCredentials
import com.blockstream.common.extensions.isConnectionError
import com.blockstream.common.extensions.isNotAuthorized
import com.blockstream.common.extensions.lightningCredentials
import com.blockstream.common.extensions.lightningMnemonic
import com.blockstream.common.extensions.logException
import com.blockstream.common.extensions.passwordPinData
import com.blockstream.common.extensions.pinPinData
import com.blockstream.common.extensions.previewWallet
import com.blockstream.common.extensions.watchOnlyCredentials
import com.blockstream.common.gdk.GdkSession
import com.blockstream.common.gdk.data.TorEvent
import com.blockstream.common.gdk.device.DeviceInterface
import com.blockstream.common.gdk.device.DeviceResolver
import com.blockstream.common.gdk.params.LoginCredentialsParams
import com.blockstream.common.lightning.AppGreenlightCredentials
import com.blockstream.common.models.GreenViewModel
import com.blockstream.common.sideeffects.SideEffect
import com.blockstream.common.sideeffects.SideEffects
import com.rickclephas.kmm.viewmodel.MutableStateFlow
import com.rickclephas.kmm.viewmodel.coroutineScope
import com.rickclephas.kmm.viewmodel.stateIn
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64

abstract class LoginViewModelAbstract(greenWallet: GreenWallet) :
    GreenViewModel(greenWalletOrNull = greenWallet) {

    override fun screenName(): String = "Login"

    @NativeCoroutinesState
    abstract val walletName: StateFlow<String>
    @NativeCoroutinesState
    abstract val bip39Passphrase: MutableStateFlow<String>
    @NativeCoroutinesState
    abstract val watchOnlyPassword: MutableStateFlow<String>
    @NativeCoroutinesState
    abstract val isEmergencyRecoveryPhrase: MutableStateFlow<Boolean>

    @NativeCoroutinesState
    abstract val tor: StateFlow<TorEvent>

    @NativeCoroutinesState
    abstract val applicationSettings: StateFlow<ApplicationSettings>

    @NativeCoroutinesState
    abstract val isWatchOnlyLoginEnabled: StateFlow<Boolean>
    @NativeCoroutinesState
    abstract val showWatchOnlyPassword: StateFlow<Boolean>

    @NativeCoroutinesState
    abstract val biometricsCredentials: StateFlow<DataState<LoginCredentials>>
    @NativeCoroutinesState
    abstract val watchOnlyCredentials: StateFlow<DataState<LoginCredentials>>
    @NativeCoroutinesState
    abstract val pinCredentials: StateFlow<DataState<LoginCredentials>>
    @NativeCoroutinesState
    abstract val passwordCredentials: StateFlow<DataState<LoginCredentials>>
    @NativeCoroutinesState
    abstract val lightningCredentials: StateFlow<DataState<LoginCredentials>>
    @NativeCoroutinesState
    abstract val lightningMnemonic: StateFlow<DataState<LoginCredentials>>


}

class LoginViewModel constructor(
    greenWallet: GreenWallet,
    val isLightningShortcut: Boolean,
    val autoLoginWallet: Boolean,
    val device: DeviceInterface? = null
) : LoginViewModelAbstract(greenWallet = greenWallet) {
    override val isLoginRequired: Boolean = false

    @NativeCoroutinesState
    override val bip39Passphrase = MutableStateFlow(viewModelScope, "")
    @NativeCoroutinesState
    override val watchOnlyPassword = MutableStateFlow(viewModelScope, "")
    @NativeCoroutinesState
    override val isEmergencyRecoveryPhrase = MutableStateFlow(viewModelScope, false)

    @NativeCoroutinesState
    override val tor = sessionManager.torProxyProgress
    @NativeCoroutinesState
    override val applicationSettings = settingsManager.appSettingsStateFlow

    private val isBip39Login
        get() = bip39Passphrase.value.trim().isNotBlank()

    private var _initialAction = MutableStateFlow(viewModelScope, false)

    private val _biometricsCredentials: MutableStateFlow<DataState<LoginCredentials>> = MutableStateFlow(viewModelScope, DataState.Loading)
    private val _watchOnlyCredentials: MutableStateFlow<DataState<LoginCredentials>> = MutableStateFlow(viewModelScope, DataState.Loading)
    private val _pinCredentials: MutableStateFlow<DataState<LoginCredentials>> = MutableStateFlow(viewModelScope, DataState.Loading)
    private val _passwordCredentials: MutableStateFlow<DataState<LoginCredentials>> = MutableStateFlow(viewModelScope, DataState.Loading)
    private val _lightningCredentials: MutableStateFlow<DataState<LoginCredentials>> = MutableStateFlow(viewModelScope, DataState.Loading)
    private val _lightningMnemonic: MutableStateFlow<DataState<LoginCredentials>> = MutableStateFlow(viewModelScope, DataState.Loading)

    @NativeCoroutinesState
    override val walletName: StateFlow<String> = greenWalletFlow.map {
        it.name
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), greenWallet.name)

    @NativeCoroutinesState
    override val biometricsCredentials: StateFlow<DataState<LoginCredentials>> = _biometricsCredentials.asStateFlow()
    @NativeCoroutinesState
    override val watchOnlyCredentials: StateFlow<DataState<LoginCredentials>> = _watchOnlyCredentials.asStateFlow()
    @NativeCoroutinesState
    override val pinCredentials: StateFlow<DataState<LoginCredentials>> = _pinCredentials.asStateFlow()
    @NativeCoroutinesState
    override val passwordCredentials: StateFlow<DataState<LoginCredentials>> = _passwordCredentials.asStateFlow()
    @NativeCoroutinesState
    override val lightningCredentials: StateFlow<DataState<LoginCredentials>> = _lightningCredentials.asStateFlow()
    @NativeCoroutinesState
    override val lightningMnemonic: StateFlow<DataState<LoginCredentials>> = _lightningMnemonic.asStateFlow()

    @NativeCoroutinesState
    override val isWatchOnlyLoginEnabled: StateFlow<Boolean> = combine(watchOnlyPassword, watchOnlyCredentials, onProgress) { watchOnlyPassword, _, onProgress ->
        (watchOnlyPassword.isNotBlank() || greenWallet.isWatchOnlySingleSig || (!watchOnlyCredentials.isEmpty() && !_initialAction.value)) && !onProgress
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)


    @NativeCoroutinesState
    override val showWatchOnlyPassword = combine(_initialAction, watchOnlyCredentials) { initialAction , watchOnlyCredentials ->
        watchOnlyCredentials.isEmpty() || initialAction
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    class LocalEvents {
        data class LoginWithPin(val pin: String): Event, Redact
        data class LoginWithBiometrics(val cipher: PlatformCipher, val loginCredentials: LoginCredentials): Event,
            Redact
        data class LoginWithBiometricsV3(val cipher: PlatformCipher, val loginCredentials: LoginCredentials): Event,
            Redact
        object LoginWatchOnly: Event
        data class LoginLightningShortcut(val isAuthenticated: Boolean = false) : Event
        object LoginWithDevice : Event
        data class Bip39Passphrase(val passphrase: String, val alwaysAsk: Boolean): Event, Redact
        data class EmergencyRecovery(val isEmergencyRecovery: Boolean): Event
        data class DeleteLoginCredentials(val loginCredentials: LoginCredentials) : Event
    }

    class LocalSideEffects {
        class LaunchBiometrics(val loginCredentials: LoginCredentials): SideEffect
        object LaunchUserPresenceForLightning : SideEffect
        object AskBip39Passphrase : SideEffect
        object PinError : SideEffect
    }

    init {

        if(session.isConnected && !isLightningShortcut){
            postSideEffect(SideEffects.Navigate(greenWallet))
        }else{
            if (greenWallet.askForBip39Passphrase) {
                postSideEffect(LocalSideEffects.AskBip39Passphrase)
            }

            device?.let {
                loginWithDevice()
            }

            // Beware as this will fire new values if eg. you change a login credential
            database.getLoginCredentialsFlow(greenWallet.id).onEach {
                _biometricsCredentials.value = DataState.successOrEmpty(it.biometricsPinData)
                _watchOnlyCredentials.value = DataState.successOrEmpty(it.watchOnlyCredentials)
                _pinCredentials.value = DataState.successOrEmpty(it.pinPinData)
                _passwordCredentials.value = DataState.successOrEmpty(it.passwordPinData)
                _lightningCredentials.value = DataState.successOrEmpty(it.lightningCredentials)
                _lightningMnemonic.value = DataState.successOrEmpty(it.lightningMnemonic)

                val biometricsBasedCredentials = (it.biometricsPinData ?: it.biometricsWatchOnlyCredentials)

                if((autoLoginWallet || isLightningShortcut) && !_initialAction.value && !greenWallet.askForBip39Passphrase){
                    if(autoLoginWallet) {
                        biometricsBasedCredentials?.let { biometricsCredentials ->
                            postSideEffect(LocalSideEffects.LaunchBiometrics(biometricsCredentials))
                            _initialAction.value = true
                        }
                    }else if(isLightningShortcut) {
                        // Ask for user presence if biometrics can be used and the parent session is not connected
                        if(greenKeystore.canUseBiometrics() && !session.isConnected){
                            postSideEffect(LocalSideEffects.LaunchUserPresenceForLightning)
                        }else{
                            lightningShortcutLogin(greenWallet.lightningShortcutWallet())
                        }
                    }
                }
            }.launchIn(viewModelScope.coroutineScope)
        }

        bootstrap()
    }

    override fun handleEvent(event: Event) {
        super.handleEvent(event)
        when (event) {
            is LocalEvents.LoginLightningShortcut -> {
                if(event.isAuthenticated || !greenKeystore.canUseBiometrics()) {
                    loginLightningShortcut()
                } else {
                    postSideEffect(LocalSideEffects.LaunchUserPresenceForLightning)
                }
            }
            is LocalEvents.LoginWithDevice -> {
                loginWithDevice()
            }
            is LocalEvents.LoginWithPin -> {
                (pinCredentials.value.data() ?: passwordCredentials.value.data())?.also {
                    loginWithPin(pin = event.pin, loginCredentials = it)
                } ?: run {
                    postSideEffect(SideEffects.ErrorDialog(Exception("No Pin Credentials or Password Credentials")))
                }
            }
            is LocalEvents.LoginWithBiometrics -> {
                loginWithBiometrics(cipher = event.cipher, loginCredentials = event.loginCredentials)
            }
            is LocalEvents.LoginWithBiometricsV3 -> {
                loginWithBiometricsV3(cipher = event.cipher, loginCredentials = event.loginCredentials)
            }
            is LocalEvents.DeleteLoginCredentials -> {
                deleteLoginCredentials(loginCredentials = event.loginCredentials)
            }
            is LocalEvents.EmergencyRecovery -> {
                bip39Passphrase.value = ""
                isEmergencyRecoveryPhrase.value = event.isEmergencyRecovery
            }
            is LocalEvents.Bip39Passphrase -> {
                setBip39Passphrase(event.passphrase, event.alwaysAsk)
            }
            is LocalEvents.LoginWatchOnly -> {
                watchOnlyCredentials.data().also {
                    if(it?.credential_type == CredentialType.BIOMETRICS_WATCHONLY_CREDENTIALS){
                        postSideEffect(LocalSideEffects.LaunchBiometrics(it))
                    }else{
                        if(!_initialAction.value && it != null){
                            loginWatchOnlyWithLoginCredentials(it)
                        }else{
                            watchOnlyLogin()
                        }
                    }
                }
            }
        }
    }

    private fun setBip39Passphrase(passphrase: String, alwaysAsk: Boolean){
        bip39Passphrase.value = passphrase.trim()
        viewModelScope.coroutineScope.launch(context = logException(countly)) {
            greenWallet.also {
                it.askForBip39Passphrase = alwaysAsk
                database.updateWallet(it)
            }
        }

        if(!_initialAction.value){
            biometricsCredentials.data()?.let { biometricsCredentials ->
                postSideEffect(LocalSideEffects.LaunchBiometrics(biometricsCredentials))
                _initialAction.value = true
            }
        }
    }

    private fun emergencyRecoveryPhrase(pin: String, loginCredentials: LoginCredentials) {
        doAsync({
            session.emergencyRestoreOfRecoveryPhrase(
                wallet = greenWallet,
                pin = pin,
                loginCredentials = loginCredentials
            ).also {
                session.disconnect()
            }
        }, onSuccess = {credentials ->
            postSideEffect(SideEffects.Navigate(credentials))
            isEmergencyRecoveryPhrase.value = false
        }, onError = {

            if (it.isNotAuthorized()) {
                viewModelScope.coroutineScope.launch {
                    database.replaceLoginCredential(loginCredentials.copy(counter = loginCredentials.counter + 1))
                }
            } else {
                postSideEffect(SideEffects.ErrorSnackbar(it))
            }

            postSideEffect(LocalSideEffects.PinError)
            countly.failedWalletLogin(session, it)
        })
    }

    private fun loginLightningShortcut() {
        val lightningWallet = greenWallet.lightningShortcutWallet()

        val lightningSession = sessionManager.getWalletSessionOrCreate(lightningWallet)

        if (lightningSession.isConnected) {
            postSideEffect(SideEffects.Navigate(lightningWallet))
        } else {
            lightningShortcutLogin(lightningWallet = lightningWallet)
        }
    }

    private fun loginWithDevice() {
        device?.gdkHardwareWallet?.also { gdkHardwareWallet ->
            login {
                session.loginWithDevice(
                    wallet = greenWallet,
                    device = device,
                    hardwareWalletResolver = DeviceResolver(gdkHardwareWallet, this),
                    hwInteraction = this
                )
            }
        }
    }

    private fun loginWithPin(pin: String, loginCredentials: LoginCredentials) {
        if(isEmergencyRecoveryPhrase.value){
            emergencyRecoveryPhrase(pin, loginCredentials)
            return
        }

        // If Wallet Hash ID is empty (from migration) do a one-time wallet restore to make a real account discovery
        val isRestore = greenWallet.xPubHashId.isEmpty()

        val appGreenlightCredentials = try {
            lightningCredentials.data().takeIf { !isBip39Login }?.encrypted_data?.let { ed ->
                greenKeystore.decryptData(ed).let { AppGreenlightCredentials.fromJsonString(it.decodeToString()) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        login(loginCredentials) {
            // if bip39 passphrase, don't initialize the session as we need to re-connect || initializeSession = bip39Passphrase.isNullOrBlank())
            session.loginWithPin(
                wallet = greenWallet,
                pin = pin,
                loginCredentials = loginCredentials,
                appGreenlightCredentials = appGreenlightCredentials,
                isRestore = isRestore,
                initializeSession = !isBip39Login
            )
        }
    }

    private fun loginWatchOnlyWithLoginCredentials(loginCredentials: LoginCredentials) {
        loginCredentials.encrypted_data?.let { encryptedData ->
            login(loginCredentials, isWatchOnly = true, updateWatchOnlyPassword = false) {

                _initialAction.value = true

                val watchOnlyCredentials = greenKeystore.decryptData(encryptedData).let {
                    if(loginCredentials.credential_type == CredentialType.KEYSTORE_PASSWORD){
                        WatchOnlyCredentials(
                            password = it.decodeToString()
                        )
                    }else{
                        WatchOnlyCredentials.fromByteArray(it)
                    }
                }

                session.loginWatchOnly(wallet = greenWallet, username = greenWallet.watchOnlyUsername ?: "", watchOnlyCredentials)
            }
        }
    }

    private fun loginWatchOnlyWithWatchOnlyCredentials(loginCredentials: LoginCredentials, watchOnlyCredentials: WatchOnlyCredentials){
        login(loginCredentials, isWatchOnly = true, updateWatchOnlyPassword = false) {
            session.loginWatchOnly(wallet = greenWallet, username = greenWallet.watchOnlyUsername ?: "", watchOnlyCredentials)
        }
    }

    private fun loginWithBiometrics(cipher: PlatformCipher, loginCredentials: LoginCredentials) {
        loginCredentials.encrypted_data?.let { encryptedData ->
            try {
                greenKeystore.decryptData(cipher, encryptedData)
            }catch (e: Exception){
                countly.recordException(e)
                postSideEffect(SideEffects.ErrorDialog(e))
                null
            }
        }?.also { decryptedData ->
            if(loginCredentials.credential_type == CredentialType.BIOMETRICS_PINDATA){
                loginWithPin(decryptedData.decodeToString(), loginCredentials)
            }else{
                loginWatchOnlyWithWatchOnlyCredentials(loginCredentials, WatchOnlyCredentials.fromByteArray(decryptedData))
            }
        }
    }

    private fun loginWithBiometricsV3(cipher: PlatformCipher, loginCredentials: LoginCredentials) {
        loginCredentials.encrypted_data?.also { encryptedData ->
            try {
                val decrypted = greenKeystore.decryptData(cipher, encryptedData)
                // Migrated from v3
                val pin = Base64.encode(decrypted).substring(0, 15)
                loginWithPin(pin, loginCredentials)
            }catch (e: Exception){
                countly.recordException(e)
                postSideEffect(SideEffects.ErrorDialog(e))
            }
        }
    }

    private fun watchOnlyLogin() {
        login(null, isWatchOnly = true, updateWatchOnlyPassword = !greenWallet.isWatchOnlySingleSig) {
            session.loginWatchOnly(
                wallet = greenWallet,
                username = greenWallet.watchOnlyUsername ?: "",
                watchOnlyCredentials = WatchOnlyCredentials(password = watchOnlyPassword.value)
            )
        }
    }

    private fun login(
        loginCredentials: LoginCredentials? = null,
        isWatchOnly: Boolean = false,
        updateWatchOnlyPassword: Boolean = false,
        logInMethod: suspend (GdkSession) -> Unit
    ) {

        doAsync({
            countly.loginWalletStart()

            logInMethod.invoke(session)

            // Migrate - add walletHashId
            if (greenWallet.xPubHashId != session.xPubHashId) {
                greenWallet.also {
                    it.xPubHashId = session.xPubHashId ?: ""
                    if (!it.isEphemeral) {
                        database.updateWallet(it)
                    }
                }
            }

            // Change active account if necessary (account archived)
            if (greenWallet.activeNetwork != (session.activeAccount.value?.networkId ?: "") ||
                greenWallet.activeAccount != (session.activeAccount.value?.pointer ?: 0)
            ) {
                greenWallet.also {
                    it.activeNetwork = session.activeAccount.value?.networkId ?: session.defaultNetwork.id
                    it.activeAccount = session.activeAccount.value?.pointer ?: 0

                    if(!it.isEphemeral) {
                        database.updateWallet(it)
                    }
                }
            }

            // Reset counter
            loginCredentials?.also{
                database.replaceLoginCredential(it.copy(counter = 0))
            }

            // Update watch-only password if needed
            if(updateWatchOnlyPassword && watchOnlyCredentials.data()?.credential_type != CredentialType.BIOMETRICS_WATCHONLY_CREDENTIALS){
                watchOnlyCredentials.data()?.let {
                    // Delete deprecated credential type
                    if(it.credential_type == CredentialType.KEYSTORE_PASSWORD){
                        database.deleteLoginCredentials(it)
                    }

                    // Upgrade from old KEYSTORE_PASSWORD if required
                    database.replaceLoginCredential(it.copy(
                        credential_type = CredentialType.KEYSTORE_WATCHONLY_CREDENTIALS,
                        encrypted_data = greenKeystore.encryptData(WatchOnlyCredentials(password = watchOnlyPassword.value).toJson().encodeToByteArray())
                    ))
                }
            }

            if(isBip39Login){
                val network = session.defaultNetwork

                val mnemonic = session.getCredentials().mnemonic

                // Disconnect as no longer needed
                session.disconnectAsync()

                val walletName = greenWallet.name

                var ephemeralWallet = GreenWallet.createEphemeralWallet(
                    ephemeralId = sessionManager.getNextEphemeralId(),
                    name = walletName,
                    networkId = network.id,
                    isHardware = false
                )

                // Create an ephemeral session
                val ephemeralSession = sessionManager.getWalletSessionOrCreate(ephemeralWallet)

                val loginData = ephemeralSession.loginWithMnemonic(
                    isTestnet = greenWallet.isTestnet,
                    loginCredentialsParams = LoginCredentialsParams(
                        mnemonic = mnemonic,
                        bip39Passphrase = bip39Passphrase.value.trim()
                    ),
                    initializeSession = true,
                    isSmartDiscovery = true,
                    isCreate = false,
                    isRestore = false
                )

                // Check if there is already a BIP39 ephemeral wallet
                val pair = sessionManager.getEphemeralWalletSession(loginData.walletHashId)?.let {
                    // Disconnect the no longer needed session
                    ephemeralSession.disconnectAsync()

                    // Return the previous connected BIP39 ephemeral session
                    it.ephemeralWallet!! to it
                } ?: run {
                    ephemeralWallet = ephemeralWallet.copy(wallet = ephemeralWallet.wallet.copy(xpub_hash_id = loginData.walletHashId))

                    // Return the ephemeral wallet
                    ephemeralWallet to ephemeralSession
                }

                pair
            }else{
                // Return the wallet
                val pair = greenWallet to session

                pair
            }
        }, postAction = {
            onProgress.value = it == null
        }, onSuccess = { pair ->
            countly.loginWalletEnd(
                wallet = pair.first,
                session = pair.second,
                loginCredentials = loginCredentials
            )
            postSideEffect(SideEffects.Navigate(pair.first))
        }, onError = {

            if(device == null) {
                if (it.isNotAuthorized()) {
                    loginCredentials?.also { loginCredentials ->

                        viewModelScope.coroutineScope.launch {
                            database.replaceLoginCredential(loginCredentials.copy(counter = loginCredentials.counter + 1))
                        }
                    }

                    if (isWatchOnly) {
                        postSideEffect(SideEffects.ErrorSnackbar(it))
                    }

                } else if (isBip39Login && it.message == "id_login_failed") {
                    // On Multisig & BIP39 Passphrase login, instead of registering a new wallet, show error "Wallet not found"
                    // Jade users restoring can still login
                    postSideEffect(SideEffects.ErrorSnackbar(Exception("id_wallet_not_found")))
                } else {
                    val errorReport = ErrorReport.create(throwable = it, session = session)

                    if (it.isConnectionError()) {
                        postSideEffect(
                            SideEffects.ErrorSnackbar(
                                error = it,
                                errorReport = errorReport
                            )
                        )
                    } else {
                        postSideEffect(
                            SideEffects.ErrorDialog(
                                error = it,
                                errorReport = errorReport
                            )
                        )
                    }
                }

                postSideEffect(LocalSideEffects.PinError)
            } else {
                val errorReport = ErrorReport.create(throwable = it, session = session)
                postSideEffect(SideEffects.NavigateBack(error = it, errorReport = errorReport))
            }

            countly.failedWalletLogin(session, it)
        })
    }

    private fun lightningShortcutLogin(lightningWallet: GreenWallet) {
        doAsync({
            val mnemonic = lightningMnemonic.data()?.encrypted_data?.let {
                greenKeystore.decryptData(it).decodeToString()
            } ?: throw Exception("Can't decrypt your lightning mnemonic.")

            countly.loginWalletStart()

            // Create an ephemeral lightning session
            val lightningSession = sessionManager.getWalletSessionOrCreate(lightningWallet)

            lightningSession.loginLightningShortcut(
                wallet = lightningWallet,
                mnemonic = mnemonic
            )

            lightningWallet to lightningSession

        }, postAction = {
            onProgress.value = it == null
        }, onSuccess = { pair ->
            countly.loginWalletEnd(
                wallet = pair.first,
                session = pair.second,
            )
            postSideEffect(SideEffects.Navigate(pair.first))
        }, onError = {
            if(device == null){
                postSideEffect(SideEffects.ErrorSnackbar(it))
                postSideEffect(LocalSideEffects.PinError)
            }else{
                postSideEffect(SideEffects.NavigateBack(it))
            }

            countly.failedWalletLogin(session, it)
        })
    }

    private fun deleteLoginCredentials(loginCredentials: LoginCredentials){
        applicationScope.launch(context = logException(countly)) {
            database.deleteLoginCredentials(loginCredentials)
        }
    }


}

class LoginViewModelPreview(greenWallet: GreenWallet) : LoginViewModelAbstract(greenWallet = greenWallet) {
    override val walletName: StateFlow<String> = MutableStateFlow(viewModelScope, "Name")
    override val bip39Passphrase: MutableStateFlow<String> = MutableStateFlow(viewModelScope, "")
    override val watchOnlyPassword: MutableStateFlow<String> = MutableStateFlow(viewModelScope, "")
    override val isEmergencyRecoveryPhrase: MutableStateFlow<Boolean> = MutableStateFlow(viewModelScope, false)
    override val tor: StateFlow<TorEvent> = MutableStateFlow(viewModelScope, TorEvent(progress = 0))
    override val applicationSettings: StateFlow<ApplicationSettings> = MutableStateFlow(viewModelScope, ApplicationSettings())
    override val isWatchOnlyLoginEnabled: StateFlow<Boolean> = MutableStateFlow(viewModelScope, false)
    override val showWatchOnlyPassword: StateFlow<Boolean> = MutableStateFlow(viewModelScope, false)
    override val biometricsCredentials: StateFlow<DataState<LoginCredentials>> = MutableStateFlow(viewModelScope, DataState.Empty)
    override val watchOnlyCredentials: StateFlow<DataState<LoginCredentials>> = MutableStateFlow(viewModelScope, DataState.Empty)
    override val pinCredentials: StateFlow<DataState<LoginCredentials>> = MutableStateFlow(viewModelScope, DataState.Empty)
    override val passwordCredentials: StateFlow<DataState<LoginCredentials>> = MutableStateFlow(viewModelScope, DataState.Empty)
    override val lightningCredentials: StateFlow<DataState<LoginCredentials>> = MutableStateFlow(viewModelScope, DataState.Empty)
    override val lightningMnemonic: StateFlow<DataState<LoginCredentials>> = MutableStateFlow(viewModelScope, DataState.Empty)

    companion object{
        fun preview(): LoginViewModelPreview{
            return LoginViewModelPreview(
                greenWallet = previewWallet(isHardware = false)
            )
        }
    }
}