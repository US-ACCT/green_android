package com.blockstream.green.ui.overview

import android.content.DialogInterface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.RecyclerView
import com.blockstream.common.Urls
import com.blockstream.common.data.AlertType
import com.blockstream.common.data.GreenWallet
import com.blockstream.common.data.SetupArgs
import com.blockstream.common.events.Events
import com.blockstream.common.extensions.needs2faActivation
import com.blockstream.common.gdk.EnrichedAssetPair
import com.blockstream.common.gdk.GdkSession
import com.blockstream.common.gdk.data.Transaction
import com.blockstream.common.lightning.inboundLiquiditySatoshi
import com.blockstream.common.lightning.isLoading
import com.blockstream.common.lightning.onchainBalanceSatoshi
import com.blockstream.common.models.GreenViewModel
import com.blockstream.common.models.overview.AccountOverviewViewModel
import com.blockstream.common.sideeffects.SideEffect
import com.blockstream.common.sideeffects.SideEffects
import com.blockstream.green.R
import com.blockstream.green.databinding.AccountOverviewFragmentBinding
import com.blockstream.green.databinding.ListItemLightningInfoBinding
import com.blockstream.green.extensions.setNavigationResult
import com.blockstream.green.extensions.showPopupMenu
import com.blockstream.green.extensions.snackbar
import com.blockstream.green.ui.AppFragment
import com.blockstream.green.ui.bottomsheets.AssetDetailsBottomSheetFragment
import com.blockstream.green.ui.bottomsheets.Call2ActionBottomSheetDialogFragment
import com.blockstream.green.ui.bottomsheets.CameraBottomSheetDialogFragment
import com.blockstream.green.ui.bottomsheets.LightningNodeBottomSheetFragment
import com.blockstream.green.ui.bottomsheets.RenameAccountBottomSheetDialogFragment
import com.blockstream.green.ui.bottomsheets.TwoFactorResetBottomSheetDialogFragment
import com.blockstream.green.ui.dialogs.LightningShortcutDialogFragment
import com.blockstream.green.ui.items.AccountWarningListItem
import com.blockstream.green.ui.items.AccountsListItem
import com.blockstream.green.ui.items.AlertListItem
import com.blockstream.green.ui.items.AssetListItem
import com.blockstream.green.ui.items.ContentCardListItem
import com.blockstream.green.ui.items.LightningInfoListItem
import com.blockstream.green.ui.items.ProgressListItem
import com.blockstream.green.ui.items.TextListItem
import com.blockstream.green.ui.items.TitleListItem
import com.blockstream.green.ui.items.TransactionListItem
import com.blockstream.green.utils.StringHolder
import com.blockstream.green.utils.copyToClipboard
import com.blockstream.green.utils.observeFlow
import com.blockstream.green.utils.observeList
import com.blockstream.green.utils.openBrowser
import com.blockstream.green.views.AccordionListener
import com.blockstream.green.views.EndlessRecyclerOnScrollListener
import com.blockstream.green.views.NpaLinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.GenericFastAdapter
import com.mikepenz.fastadapter.GenericItem
import com.mikepenz.fastadapter.adapters.FastItemAdapter
import com.mikepenz.fastadapter.adapters.GenericFastItemAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import com.mikepenz.fastadapter.adapters.ModelAdapter
import com.mikepenz.fastadapter.binding.listeners.addClickListener
import com.mikepenz.itemanimators.SlideDownAlphaAnimator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf


class AccountOverviewFragment : AppFragment<AccountOverviewFragmentBinding>(
    layout = R.layout.account_overview_fragment,
    menuRes = R.menu.account_overview
), OverviewInterface {
    val args: AccountOverviewFragmentArgs by navArgs()

    override val appFragment: AccountOverviewFragment
        get() = this

    val viewModel: AccountOverviewViewModel by viewModel {
        parametersOf(args.wallet, args.accountAsset)
    }

    override fun getGreenViewModel(): GreenViewModel = viewModel

    var fastAdapter: FastAdapter<GenericItem>? = null

    override val title: String
        get() = viewModel.greenWallet.name

    override fun handleSideEffect(sideEffect: SideEffect) {
        super.handleSideEffect(sideEffect)
        if (sideEffect is SideEffects.NavigateToRoot) {
            findNavController().popBackStack(R.id.walletOverviewFragment, false)
        } else if (sideEffect is SideEffects.Navigate){
            if(sideEffect.data is String){
                if (sideEffect.data == WalletOverviewFragment.ACCOUNT_ARCHIVED) {
                    setNavigationResult(
                        result = true,
                        key = WalletOverviewFragment.ACCOUNT_ARCHIVED,
                        destinationId = R.id.walletOverviewFragment
                    )
                    findNavController().popBackStack(R.id.walletOverviewFragment, false)
                }
            }
        } else if (sideEffect is SideEffects.OpenDialog){
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.id_warning)
                .setMessage(if (viewModel.account.network.isLiquid) R.string.id_insufficient_lbtc_to_send_a else R.string.id_you_have_no_coins_to_send)
                .also {
                    if (viewModel.account.network.isLiquid) {
                        it.setPositiveButton(R.string.id_learn_more) { _: DialogInterface, _: Int ->
                            openBrowser(Urls.HELP_GET_LIQUID)
                        }
                    } else {
                        it.setPositiveButton(R.string.id_receive) { _: DialogInterface, _: Int ->
                            navigate(
                                AccountOverviewFragmentDirections.actionAccountOverviewFragmentToReceiveFragment(
                                    wallet = viewModel.greenWallet,
                                    accountAsset = viewModel.accountAsset.value!!
                                )
                            )
                        }
                    }
                }
                .setNegativeButton(R.string.id_cancel, null)
                .show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        overviewSetup()

        binding.vm = viewModel
        binding.bottomNav.isWatchOnly = wallet.isWatchOnly
        binding.bottomNav.sweepEnabled = session.defaultNetwork.isBitcoin
        binding.bottomNav.showSwap = false //account.isLiquid && account.isMultisig

        viewModel.hasLightningShortcut.onEach {
            // Update menu for LN Shortcut
            invalidateMenu()
        }.launchIn(lifecycleScope)

        val fastAdapter = setupAdapters(binding.recycler)

        binding.bottomNav.buttonCamera.setOnClickListener {
            CameraBottomSheetDialogFragment.showSingle(screenName = screenName, fragmentManager = childFragmentManager)
        }

        binding.bottomNav.buttonReceive.setOnClickListener {
            navigate(
                AccountOverviewFragmentDirections.actionAccountOverviewFragmentToReceiveFragment(
                    wallet = viewModel.greenWallet,
                    accountAsset = viewModel.account.accountAsset,
                )
            )
        }

        binding.bottomNav.buttonSend.setOnClickListener {
            viewModel.postEvent(AccountOverviewViewModel.LocalEvents.Send)
        }

        fastAdapter.stateRestorationPolicy =
            RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY

        binding.recycler.apply {
            layoutManager = NpaLinearLayoutManager(context)
            itemAnimator = SlideDownAlphaAnimator()
            adapter = fastAdapter
        }

        binding.swipeRefreshLayout.setOnRefreshListener {
            binding.swipeRefreshLayout.isRefreshing = false
            viewModel.postEvent(AccountOverviewViewModel.LocalEvents.Refresh)
        }
    }

    override fun onPrepareMenu(menu: Menu) {
        // Prevent from archiving all your accounts
        menu.findItem(R.id.archive).isVisible = !session.isWatchOnly && viewModel.accounts.value.size > 1 && !viewModel.account.isLightning
        menu.findItem(R.id.help).isVisible = viewModel.account.isAmp
        menu.findItem(R.id.enhance_security).isVisible = !session.isWatchOnly && viewModel.account.needs2faActivation(session)
        menu.findItem(R.id.rename).isVisible = !session.isWatchOnly && !viewModel.account.isLightning
        menu.findItem(R.id.node_info).isVisible = viewModel.account.isLightning
        menu.findItem(R.id.remove).isVisible = viewModel.account.isLightning && !wallet.isLightning

        menu.findItem(R.id.lightning_shortcut).also {
            // Only allow the removal of LN Shortcut on Wallet
            it.isVisible = viewModel.account.isLightning && !wallet.isLightning && (viewModel.hasLightningShortcut.value == true || !wallet.isHardware)
            it.title = getString(if(viewModel.hasLightningShortcut.value == true) R.string.id_remove_lightning_shortcut else R.string.id_add_lightning_shortcut)
            it.icon = ContextCompat.getDrawable(requireContext(), if(viewModel.hasLightningShortcut.value == true) R.drawable.ic_lightning_slash else R.drawable.ic_lightning)
        }
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.help -> {
                openBrowser(Urls.HELP_AMP_ASSETS)
                true
            }
            R.id.rename -> {
                RenameAccountBottomSheetDialogFragment.show(
                    viewModel.account,
                    childFragmentManager
                )
                true
            }
            R.id.archive -> {
                viewModel.postEvent(Events.ArchiveAccount(viewModel.account))
                true
            }
            R.id.enhance_security -> {
                navigate(AccountOverviewFragmentDirections.actionGlobalTwoFractorAuthenticationFragment(
                    wallet = wallet,
                    network = viewModel.account.network
                ))
                true
            }
            R.id.node_info-> {
                LightningNodeBottomSheetFragment.show(childFragmentManager)
                true
            }
            R.id.lightning_shortcut -> {
                if (viewModel.hasLightningShortcut.value == true) {
                    viewModel.postEvent(AccountOverviewViewModel.LocalEvents.RemoveLightningShortcut)

                } else {
                    LightningShortcutDialogFragment.show(fragmentManager = childFragmentManager)
                }
                true
            }
            R.id.remove-> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.id_remove)
                    .setMessage(R.string.id_are_you_sure_you_want_to_remove)
                    .setPositiveButton(R.string.id_remove) { _, _ ->
                        viewModel.postEvent(Events.RemoveAccount(viewModel.account))
                        snackbar(R.string.id_account_has_been_removed)
                    }
                    .setNegativeButton(R.string.id_cancel, null)
                    .show()

                true
            }
            else -> super.onMenuItemSelected(menuItem)
        }
    }

    override fun onResume() {
        super.onResume()

        requireActivity().window.navigationBarColor =
            ContextCompat.getColor(requireContext(), R.color.brand_surface_variant)
    }

    override fun onPause() {
        super.onPause()

        requireActivity().window.navigationBarColor =
            ContextCompat.getColor(requireContext(), R.color.brand_background)
    }

    private fun setupAdapters(recycler: RecyclerView): GenericFastAdapter {

        val accountAdapter: GenericFastItemAdapter = FastItemAdapter()

        viewModel.accountAsset.filterNotNull().onEach {
            AccountsListItem(
                session = session,
                accounts = listOf(it.account),
                showArrow = false,
                show2faActivation = false,
                showCopy = viewModel.account.isAmp,
                listener = object :
                    AccordionListener {
                    override fun expandListener(view: View, position: Int) { }

                    override fun arrowClickListener(view: View, position: Int) { }

                    override fun copyClickListener(view: View, position: Int) {
                        copyToClipboard("AccountID", viewModel.account.receivingId, requireContext())
                        snackbar(R.string.id_copied_to_clipboard)
                    }

                    override fun warningClickListener(view: View, position: Int) { }

                    override fun longClickListener(view:View, position: Int) {

                        val menu = if(viewModel.account.isLightning) R.menu.menu_account_remove else if (viewModel.accounts.value.size == 1) R.menu.menu_account else R.menu.menu_account_archive
                        showPopupMenu(view, menu) { menuItem ->
                            when (menuItem.itemId) {
                                R.id.rename -> {
                                    RenameAccountBottomSheetDialogFragment.show(
                                        viewModel.account,
                                        childFragmentManager
                                    )
                                }

                                R.id.archive -> {
                                    viewModel.postEvent(Events.ArchiveAccount(viewModel.account))
                                }

                                R.id.remove -> {
                                    viewModel.postEvent(Events.RemoveAccount(viewModel.account))
                                }
                            }
                            true
                        }

                    }

                }).also {
                accountAdapter.set(listOf(it))
            }

            // Update toolbar to shot the account name
            updateToolbar()
        }.launchIn(lifecycleScope)

        // AMP Id
        val ampAccountHelpAdapter: GenericFastItemAdapter = FastItemAdapter()

        // Assets Balance
        @Suppress("UNCHECKED_CAST")
        val assetsBalanceAdapter = ModelAdapter<EnrichedAssetPair, AssetListItem> {
            AssetListItem(
                session = session,
                assetPair = it,
                showBalance = true,
                isLoading = (it.first.assetId.isEmpty() && it.second == -1L)
            )
        }.observeFlow(
            lifecycleScope,
            viewModel.assets,
            toList = {
                it.map {
                    EnrichedAssetPair(it.key, it.value)
                }
            })

        val lightningInboundAdapter: GenericFastItemAdapter = FastItemAdapter()

        if(viewModel.account.isLightning){
            // Hide Inbound liquidity if it's zero
            session.lightningNodeInfoStateFlow.filter { !it.isLoading() && (it.onchainBalanceSatoshi() > 0 || it.inboundLiquiditySatoshi() > 0) }
                .onEach {
                lightningInboundAdapter.set(
                    listOf(
                        LightningInfoListItem(session = session, nodeState = it)
                    )
                )
            }.launchIn(lifecycleScope)
        }

        val call2ActionAdapter: GenericFastItemAdapter = FastItemAdapter()

        viewModel.assets.onEach {
            // Disabled
            if(viewModel.account.isMultisig) {
                if (viewModel.account.needs2faActivation(session)) {
                    call2ActionAdapter.set(
                        listOf(
                            AccountWarningListItem(
                                account = viewModel.account,
                                style = 1
                            )
                        )
                    )
                } else {
                    call2ActionAdapter.set(emptyList())
                }
            }else{
//                val noMultisigAccount = session.accounts.firstOrNull { it.isMultisig && it.isBitcoin == network.isBitcoin } == null
//                if (noMultisigAccount && account.isSinglesig && (it[network.policyAsset] ?: 0) >= singlesigWarningThreshold) {
//                    call2ActionAdapter.set(
//                        listOf(
//                            AccountWarningListItem(
//                                account = account
//                            )
//                        )
//                    )
//                } else {
//                    call2ActionAdapter.set(emptyList())
//                }
            }

            if (viewModel.account.isAmp && it.isEmpty()) {
                ampAccountHelpAdapter.set(
                    listOf(
                        ContentCardListItem(
                            title = StringHolder(R.string.id_learn_more_about_amp_the_assets),
                            caption = StringHolder(R.string.id_check_our_6_easy_steps_to_be)
                        )
                    )
                )
            }else {
                ampAccountHelpAdapter.clear()
            }
        }.launchIn(lifecycleScope)

        // Alert cards
        val alertCardsAdapter = ModelAdapter<AlertType, GenericItem> {
            AlertListItem(it).also { alertListItem ->
                alertListItem.action = { _ ->
                    when (alertListItem.alertType) {
                        is AlertType.Reset2FA -> {
                            TwoFactorResetBottomSheetDialogFragment.show(
                                alertListItem.alertType.network,
                                alertListItem.alertType.twoFactorReset,
                                childFragmentManager
                            )
                        }
                        is AlertType.Dispute2FA -> {
                            TwoFactorResetBottomSheetDialogFragment.show(
                                alertListItem.alertType.network,
                                alertListItem.alertType.twoFactorReset,
                                childFragmentManager
                            )
                        }
                        else -> {}
                    }
                }
            }
        }.observeList(lifecycleScope, viewModel.alerts)

        val transactionTitleAdapter = FastItemAdapter<GenericItem>()

        viewModel.transactions.onEach {  transactions ->
            mutableListOf<GenericItem>(TitleListItem(StringHolder(R.string.id_transactions))).also { list ->
                if(transactions.isEmpty()){
                    list += TextListItem(
                        text = StringHolder(R.string.id_your_transactions_will_be_shown),
                        textColor = R.color.color_on_surface_emphasis_low
                    )
                }
            }.also {
                transactionTitleAdapter.set(it)
            }
        }.launchIn(lifecycleScope)

        val transactionsFooterAdapter = ItemAdapter<GenericItem>()

        val endlessRecyclerOnScrollListener = object : EndlessRecyclerOnScrollListener(recycler) {
            override fun onLoadMore() {
                transactionsFooterAdapter.set(listOf(ProgressListItem()))
                disable()
                viewModel.postEvent(AccountOverviewViewModel.LocalEvents.LoadMoreTransactions)
            }
        }.also {
            it.disable()
        }

        val transactionAdapter = ModelAdapter<Transaction, TransactionListItem> {
            TransactionListItem(it, session)
        }.observeList(lifecycleScope, viewModel.transactions)

        viewModel.hasMoreTransactions.onEach { hasMoreTransactions ->
            transactionsFooterAdapter.clear()

            if (hasMoreTransactions) {
                lifecycleScope.launch {
                    delay(200L)
                    endlessRecyclerOnScrollListener.enable()
                }
            } else {
                endlessRecyclerOnScrollListener.disable()
            }
        }.launchIn(lifecycleScope)

        recycler.addOnScrollListener(endlessRecyclerOnScrollListener)

        val adapters = listOfNotNull(
            accountAdapter,
            ampAccountHelpAdapter,
            alertCardsAdapter,
            assetsBalanceAdapter,
            lightningInboundAdapter,
            call2ActionAdapter,
            transactionTitleAdapter,
            transactionAdapter,
            transactionsFooterAdapter
        )

        val fastAdapter = FastAdapter.with(adapters).also {
            this.fastAdapter = it
        }

        // Notify when 1) account & balance are update and when 2) assets are updated
        merge(session.accountsAndBalanceUpdated, session.networkAssetManager.assetsUpdateFlow).onEach {
            fastAdapter.notifyAdapterDataSetChanged()
        }.launchIn(lifecycleScope)

        // Update transactions
        session.block(viewModel.account.network).onEach {
            fastAdapter.notifyAdapterDataSetChanged()
        }.launchIn(lifecycleScope)

        fastAdapter.addClickListener<ListItemLightningInfoBinding, GenericItem>({ binding -> binding.buttonSweep }) { _, _, _, _ ->
            navigate(
                AccountOverviewFragmentDirections.actionAccountOverviewFragmentToRecoverFundsFragment(
                    wallet = wallet,
                    amount = session.lightningSdk.nodeInfoStateFlow.value.onchainBalanceSatoshi()
                )
            )
        }

        fastAdapter.addClickListener<ListItemLightningInfoBinding, GenericItem>({ binding -> binding.buttonLearnMore }) { _, _, _, _ ->
            openBrowser(Urls.HELP_RECEIVE_CAPACITY)
        }

        fastAdapter.onClickListener = { _, _, item, _ ->
            when (item) {
                is AssetListItem -> {
                    AssetDetailsBottomSheetFragment.show(
                        item.assetPair.first.assetId,
                        account = viewModel.account,
                        childFragmentManager
                    )
                }
                is AccountWarningListItem -> {
                    if(viewModel.account.isSinglesig) {
                        Call2ActionBottomSheetDialogFragment.showIncreaseSecurity(
                            viewModel.account,
                            childFragmentManager
                        )
                    }else{
                        // Navigate directly
                        navigate(AccountOverviewFragmentDirections.actionGlobalTwoFractorAuthenticationFragment(
                            wallet = wallet,
                            network = viewModel.account.network
                        ))
                    }
                }
                is ContentCardListItem -> {
                    openBrowser(Urls.HELP_AMP_ASSETS)
                }
                is TransactionListItem -> {
                    if (item.tx.isLightningSwap && !item.tx.isRefundableSwap) {
                        snackbar(R.string.id_swap_is_in_progress)
                    } else if(!item.tx.isLoadingTransaction) {
                        navigate(
                            AccountOverviewFragmentDirections.actionAccountOverviewFragmentToTransactionDetailsFragment(
                                wallet = wallet,
                                transaction = item.tx
                            )
                        )
                    }
                }
            }

            true
        }

        return fastAdapter
    }

    fun showLightningRecoveryPhrase(){
        navigate(
            AccountOverviewFragmentDirections.actionAccountOverviewFragmentToRecoveryIntroFragment(
                setupArgs = SetupArgs(mnemonic = "", isLightning = true, isShowRecovery = true, greenWallet = wallet),
            )
        )
    }

    fun showEmptyLightningAccount(){
        navigate(
            AccountOverviewFragmentDirections.actionAccountOverviewFragmentToRecoverFundsFragment(
                wallet = wallet,
                isSendAll = true
            )
        )
    }

    fun rescanSwaps(){
        viewModel.postEvent(AccountOverviewViewModel.LocalEvents.RescanSwaps)
    }

    override fun openProposal(link: String) {
        //
    }

    override val session: GdkSession
        get() = viewModel.session
    override val wallet: GreenWallet
        get() = viewModel.greenWallet
}
