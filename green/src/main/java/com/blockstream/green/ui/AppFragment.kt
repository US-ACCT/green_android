package com.blockstream.green.ui


import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.annotation.MenuRes
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.navigation.NavDirections
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.blockstream.common.ScreenView
import com.blockstream.common.ZendeskSdk
import com.blockstream.common.data.LogoutReason
import com.blockstream.common.extensions.handleException
import com.blockstream.common.gdk.Gdk
import com.blockstream.common.gdk.Wally
import com.blockstream.common.gdk.data.AccountAsset
import com.blockstream.common.managers.SessionManager
import com.blockstream.common.managers.SettingsManager
import com.blockstream.common.models.GreenViewModel
import com.blockstream.common.navigation.NavigateDestinations
import com.blockstream.common.sideeffects.SideEffect
import com.blockstream.common.sideeffects.SideEffects
import com.blockstream.green.NavGraphDirections
import com.blockstream.green.data.BannerView
import com.blockstream.green.data.CountlyAndroid
import com.blockstream.green.extensions.errorDialog
import com.blockstream.green.extensions.errorSnackbar
import com.blockstream.green.extensions.snackbar
import com.blockstream.green.extensions.stringFromIdentifier
import com.blockstream.green.extensions.stringFromIdentifierOrNull
import com.blockstream.green.ui.bottomsheets.AccountAssetListener
import com.blockstream.green.ui.bottomsheets.DenominationBottomSheetDialogFragment
import com.blockstream.green.ui.bottomsheets.DeviceInteractionRequestBottomSheetDialogFragment
import com.blockstream.green.ui.bottomsheets.PassphraseBottomSheetDialogFragment
import com.blockstream.green.ui.bottomsheets.PinMatrixBottomSheetDialogFragment
import com.blockstream.green.ui.drawer.DrawerFragment
import com.blockstream.green.utils.BannersHelper
import com.blockstream.green.utils.copyToClipboard
import com.blockstream.green.utils.openBrowser
import com.blockstream.green.views.GreenAlertView
import com.blockstream.green.views.GreenToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import mu.KLogging
import org.koin.android.ext.android.inject


/**
 * AppFragment
 *
 * This class is a useful abstract base class. Extend all other Fragments if possible.
 * Some of the features can be turned on/off in the constructor.
 *
 * @property layout the layout id of the fragment
 * is called when the fragment is not actually visible
 *
 */

abstract class AppFragment<T : ViewDataBinding>(
    @LayoutRes val layout: Int,
    @MenuRes val menuRes: Int = 0
) : Fragment(), MenuProvider, ScreenView, BannerView, AccountAssetListener {
    open val isAdjustResize = false

    internal lateinit var binding: T

    val countly: CountlyAndroid by inject()

    protected val gdk: Gdk by inject()

    protected val wally: Wally by inject()

    protected val sessionManager: SessionManager by inject()

    val zendeskSdk: ZendeskSdk by inject()

    val settingsManager: SettingsManager by inject()
    open fun getGreenViewModel(): GreenViewModel? = null

    open val title : String? = null
    open val subtitle : String? = null
    open val toolbarIcon: Int? = null

    override val screenName: String? = null
    override var screenIsRecorded = false
    override val segmentation: HashMap<String, Any>? = null

    protected val toolbar: GreenToolbar
        get() = (requireActivity() as AppActivity).toolbar

    open val useCompose : Boolean = false
    open val sideEffectsHandledByAppFragment: Boolean = true

    open fun updateToolbar() {
        title?.let {
            toolbar.title = it
        }
        toolbar.subtitle = subtitle
        toolbar.logo = null
        // Only show toolbar icon if it's overridden eg. add account flow
        toolbarIcon?.let { toolbar.setLogo(it) }
        toolbar.setBubble(null)
        toolbar.setButton(null)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = DataBindingUtil.inflate(layoutInflater, layout, container, false)
        binding.lifecycleOwner = viewLifecycleOwner

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Add MenuProvider if required
        if (menuRes > 0) {
            (requireActivity() as? MenuHost)?.addMenuProvider(
                this,
                viewLifecycleOwner,
                Lifecycle.State.RESUMED
            )
        }

        getGreenViewModel()?.let { viewModel ->

            viewModel.banner.onEach {
                BannersHelper.setupBanner(this, it)
            }.launchIn(lifecycleScope)

            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    (if(useCompose) viewModel.sideEffectReEmitted else viewModel.sideEffect).onEach {
                        handleSideEffect(it)
                    }.launchIn(this)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Prevent DrawerFragment from corrupting the main fragment
        if (this !is DrawerFragment) {
            @Suppress("DEPRECATION")
            requireActivity().window.setSoftInputMode(if (isAdjustResize) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            updateToolbar()
            countly.screenView(this)
        }

        BannersHelper.handle(this, getGreenViewModel()?.sessionOrNull)
    }

    override fun onPrepareMenu(menu: Menu) {
        // Handle for example visibility of menu items
    }

    @SuppressLint("RestrictedApi")
    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        (menu as? MenuBuilder)?.setOptionalIconsVisible(true)
        menuInflater.inflate(menuRes, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        // Validate and handle the selected menu item
        return true
    }

    protected fun invalidateMenu(){
        (requireActivity() as? MenuHost)?.invalidateMenu()
    }

    protected fun closeDrawer() {
        (requireActivity() as AppActivity).closeDrawer()
    }

    protected fun isDrawerOpen() = (requireActivity() as AppActivity).isDrawerOpen()

    fun setToolbarVisibility(isVisible: Boolean){
        (requireActivity() as AppActivity).setToolbarVisibility(isVisible)
    }

    fun navigate(directions: NavDirections, navOptionsBuilder: NavOptions.Builder? = null) {
        navigate(directions.actionId, directions.arguments, false, navOptionsBuilder)
    }

    fun navigate(@IdRes resId: Int, navOptionsBuilder: NavOptions.Builder? = null) {
        navigate(resId, null, false, navOptionsBuilder)
    }

    @SuppressLint("RestrictedApi")
    fun navigate(@IdRes resId: Int, args: Bundle?, isLogout: Boolean = false, optionsBuilder: NavOptions.Builder? = null) {
        com.blockstream.green.extensions.navigate(
            findNavController(),
            resId,
            args,
            isLogout,
            optionsBuilder
        )
    }

    internal open fun popBackStack(){
        findNavController().popBackStack()
    }

    internal open fun popBackStack(@IdRes destinationId: Int, inclusive: Boolean){
        findNavController().popBackStack(destinationId, inclusive)
    }

    open fun handleSideEffect(sideEffect: SideEffect){
        when (sideEffect){
            is SideEffects.OpenBrowser -> {
                if(sideEffectsHandledByAppFragment) {
                    openBrowser(sideEffect.url)
                }
            }
            is SideEffects.Snackbar -> {
                // Snackbar is implemented in compose but LocalSnackbar is only available in GreenApp
                view?.also {
                    Snackbar.make(
                        it,
                        requireContext().stringFromIdentifier(sideEffect.text),
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
            is SideEffects.Dialog -> {
                if (sideEffectsHandledByAppFragment) {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(
                            requireContext().stringFromIdentifierOrNull(
                                sideEffect.title
                            )
                        ).setMessage(requireContext().stringFromIdentifier(sideEffect.message))
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }
            is SideEffects.ErrorSnackbar -> {
                if(sideEffectsHandledByAppFragment) {
                    errorSnackbar(
                        throwable = sideEffect.error,
                        errorReport = sideEffect.errorReport,
                        duration = Snackbar.LENGTH_LONG
                    )
                }
            }
            is SideEffects.ErrorDialog -> {
                if(sideEffectsHandledByAppFragment) {
                    errorDialog(
                        throwable = sideEffect.error,
                        errorReport = sideEffect.errorReport,
                    )
                }
            }
            is SideEffects.DeviceRequestPin -> {
                PinMatrixBottomSheetDialogFragment.show(childFragmentManager)
            }
            is SideEffects.DeviceRequestPassphrase -> {
                PassphraseBottomSheetDialogFragment.show(childFragmentManager)
            }
            is SideEffects.DeviceInteraction -> {

                DeviceInteractionRequestBottomSheetDialogFragment.showSingle(
                    device = sideEffect.device,
                    message = sideEffect.message,
                    delay = (3000L).takeIf { sideEffect.completable == null } ?: 0L,
                    childFragmentManager
                )

                sideEffect.completable?.also { completable ->
                    lifecycleScope.launch(context = handleException()) {
                        completable.await()
                        withResumed {
                            DeviceInteractionRequestBottomSheetDialogFragment.closeAll(
                                childFragmentManager
                            )
                        }
                    }
                }
            }
            is SideEffects.OpenDenominationDialog -> {
                DenominationBottomSheetDialogFragment.show(denominatedValue = sideEffect.denominatedValue, childFragmentManager)
            }
            is SideEffects.NavigateBack -> {
                if (sideEffect.error == null) {
                        popBackStack()
                } else {
                    errorDialog(sideEffect.error!!, errorReport = sideEffect.errorReport) {
                        popBackStack()
                    }
                }
            }
            is SideEffects.CopyToClipboard -> {
                if(sideEffectsHandledByAppFragment) {
                    copyToClipboard(sideEffect.label ?: "Green", sideEffect.value, requireContext())

                    sideEffect.message?.also {
                        snackbar(requireContext().stringFromIdentifier(it))
                    }
                }
            }
            is SideEffects.Logout -> {
                getGreenViewModel()?.greenWalletOrNull?.let {
                    // If is ephemeral wallet, prefer going to intro
                    if (it.isEphemeral || it.isHardware || sideEffect.reason == LogoutReason.USER_ACTION) {
                        NavGraphDirections.actionGlobalHomeFragment()
                    } else {
                        NavGraphDirections.actionGlobalLoginFragment(it)
                    }.let { directions ->
                        navigate(directions.actionId, directions.arguments, isLogout = true)
                    }

                    true
                } ?: run {
                    NavGraphDirections.actionGlobalHomeFragment().also {
                        navigate(it.actionId, it.arguments, isLogout = true)
                    }
                }

            }

            is SideEffects.NavigateTo -> {
                (sideEffect.destination as? NavigateDestinations.AddWallet)?.also {
                    navigate(NavGraphDirections.actionGlobalAddWalletFragment())
                }

                (sideEffect.destination as? NavigateDestinations.About)?.also {
                    navigate(NavGraphDirections.actionGlobalAboutFragment())
                }

                (sideEffect.destination as? NavigateDestinations.UseHardwareDevice)?.also {
                    navigate(NavGraphDirections.actionGlobalUseHardwareDeviceFragment())
                }

                (sideEffect.destination as? NavigateDestinations.NewWatchOnlyWallet)?.also {
                    navigate(NavGraphDirections.actionGlobalWatchOnlyPolicyFragment())
                }

                (sideEffect.destination as? NavigateDestinations.AppSettings)?.also {
                    navigate(NavGraphDirections.actionGlobalAppSettingsFragment())
                }

                (sideEffect.destination as? NavigateDestinations.Receive)?.also {
                    navigate(
                        NavGraphDirections.actionGlobalReceiveFragment(
                            wallet = it.greenWallet,
                            accountAsset = it.accountAsset
                        )
                    )
                }

                (sideEffect.destination as? NavigateDestinations.Sweep)?.also {
                    navigate(
                        NavGraphDirections.actionGlobalSweepFragment(
                            wallet = it.greenWallet,
                            accountAsset = it.accountAsset,
                            privateKey = it.privateKey
                        )
                    )
                }

                (sideEffect.destination as? NavigateDestinations.Send)?.also {
                    navigate(
                        NavGraphDirections.actionGlobalSendFragment(
                            wallet = it.greenWallet,
                            accountAsset = it.accountAsset,
                            address = it.address,
                            assetId = it.assetId,
                            bumpTransaction = it.bumpTransaction
                        )
                    )
                }
            }
        }
    }

    override fun getBannerAlertView(): GreenAlertView? = null

    override fun accountAssetClicked(accountAsset: AccountAsset) {
        getGreenViewModel()?.also {
            it.accountAsset.value = accountAsset
        }
    }

    companion object: KLogging()
}