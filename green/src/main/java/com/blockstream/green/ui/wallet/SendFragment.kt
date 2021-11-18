package com.blockstream.green.ui.wallet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.distinctUntilChanged
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.blockstream.gdk.BalancePair
import com.blockstream.gdk.GreenWallet
import com.blockstream.green.R
import com.blockstream.green.data.NavigateEvent
import com.blockstream.green.databinding.EditTextDialogBinding
import com.blockstream.green.databinding.ListItemTransactionRecipientBinding
import com.blockstream.green.databinding.SendFragmentBinding
import com.blockstream.green.filters.NumberValueFilter
import com.blockstream.green.gdk.getAssetIcon
import com.blockstream.green.ui.*
import com.blockstream.green.ui.items.AssetListItem
import com.blockstream.green.ui.looks.AssetLook
import com.blockstream.green.utils.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.greenaddress.greenbits.ui.preferences.PrefKeys
import com.greenaddress.greenbits.ui.send.SendConfirmActivity
import com.mikepenz.fastadapter.GenericItem
import com.mikepenz.fastadapter.adapters.ModelAdapter
import dagger.hilt.android.AndroidEntryPoint
import java.text.NumberFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class SendFragment : WalletFragment<SendFragmentBinding>(
    layout = R.layout.send_fragment,
    menuRes = 0
), FilterableDataProvider {

    override val isAdjustResize = false

    val args: SendFragmentArgs by navArgs()

    override val wallet by lazy { args.wallet }
    val isSweep by lazy { args.isSweep }

    val bindings = mutableListOf<ListItemTransactionRecipientBinding>()

    private val startForResultReviewTransaction =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode == Activity.RESULT_OK) {
                findNavController().popBackStack(R.id.overviewFragment, false)
            }
        }

    @Inject
    lateinit var viewModelFactory: SendViewModel.AssistedFactory
    val viewModel: SendViewModel by viewModels {
        SendViewModel.provideFactory(viewModelFactory, wallet, isSweep, args.address)
    }

    override fun getWalletViewModel() = viewModel

    private val assetAdapter by lazy {
        ModelAdapter<BalancePair, AssetListItem> {
            AssetListItem(session = session, balancePair = it, showInfo = false, isLoading = false)
        }.observeMap(
            viewLifecycleOwner,
            viewModel.getAssetsLiveData() as LiveData<Map<*, *>>,
            toModel = {
                BalancePair(it.key as String, it.value as Long)
            })
            .also {
                it.itemFilter.filterPredicate = { item: AssetListItem, constraint: CharSequence? ->
                    item.name.lowercase().contains(
                        constraint.toString().lowercase()
                    )
                }
            }
    }

    override fun onViewCreatedGuarded(view: View, savedInstanceState: Bundle?) {

        getNavigationResult<String>(CameraBottomSheetDialogFragment.CAMERA_SCAN_RESULT)?.observe(
            viewLifecycleOwner
        ) {
            it?.let { result ->
                clearNavigationResult(CameraBottomSheetDialogFragment.CAMERA_SCAN_RESULT)
                viewModel.setAddress(viewModel.activeRecipient, result)
            }
        }

        // Handle pending BIP-21 uri
        sessionManager.pendingBip21Uri.observe(viewLifecycleOwner) {
            it?.getContentIfNotHandledOrReturnNull()?.let { bip21Uri ->
                viewModel.setBip21Uri(bip21Uri)
                snackbar(R.string.id_address_was_filled_by_a_payment_uri)
            }
        }

        binding.vm = viewModel
        binding.enableMultipleRecipients = false //isDevelopmentFlavor() && session.isTestnet

        viewModel.onEvent.observe(viewLifecycleOwner) { consumableEvent ->
            consumableEvent?.getContentIfNotHandledForType<NavigateEvent.Navigate>()?.let {
                    startForResultReviewTransaction.launch(
                        Intent(
                            requireContext(),
                            SendConfirmActivity::class.java
                        ).also {
                            it.putExtra(PrefKeys.SWEEP, isSweep)
                            session.hwWallet?.device?.let { device ->
                                it.putExtra("hww", device)
                            }
                        }
                    )
                }

            consumableEvent?.getContentIfNotHandledForType<NavigateEvent.NavigateBack>()?.let {
                it.reason?.let {
                    errorDialog(it){
                        popBackStack()
                    }
                } ?: popBackStack()

            }
        }

        viewModel.onError.observe(viewLifecycleOwner) {
            it?.getContentIfNotHandledOrReturnNull()?.let {
                errorDialog(it)
            }
        }

        viewModel.getRecipientsLiveData().observe(viewLifecycleOwner) {
            for (liveData in it.withIndex()) {
                updateRecipient(liveData.index, liveData.value)
            }

            // Remove views
            while (bindings.size > it.size){
                binding.recipientContainer.removeViewAt(bindings.size - 1)
                bindings.removeLastOrNull()
            }
        }

        binding.buttonAddRecipient.setOnClickListener {
            viewModel.addRecipient()
        }

        binding.buttonContinue.setOnClickListener {
            viewModel.confirmTransaction()
        }

        binding.buttonEditFee.setOnClickListener {
            setCustomFeeRate()
        }

        binding.feeSlider.setLabelFormatter { value: Float ->
            val format = NumberFormat.getCurrencyInstance()
            format.maximumFractionDigits = 0
            format.format(value.toDouble())

            getString(when(value.toInt()){
                1 -> R.string.id_slow
                2 -> R.string.id_medium
                3 -> R.string.id_fast
                else -> R.string.id_custom
            })
        }

        viewModel.feeSlider.distinctUntilChanged().observe(viewLifecycleOwner) { slider ->
            binding.expectedConfirmationTime = if(slider.toInt() == SendViewModel.SliderCustomIndex){
                getString(R.string.id_custom)
            }else{
                getExpectedConfirmationTime(requireContext(), GreenWallet.FeeBlockTarget[3 - (slider.toInt())])
            }
        }

        // Setup read-only
//        final JsonNode readOnlyNode = tx.get("addressees_read_only");
//        if (readOnlyNode != null && readOnlyNode.asBoolean()) {
//            mAmountText.setEnabled(false);
//            mSendAllButton.setVisibility(View.GONE);
//            mAccountBalance.setVisibility(View.GONE);
//        } else {
//            mAmountText.requestFocus();
//            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
//        }

    }

    private fun getExpectedConfirmationTime(context: Context, blocks: Int): String {
        val blocksPerHour = session.network.blocksPerHour
        val n = if (blocks % blocksPerHour == 0) blocks / blocksPerHour else blocks * (60 / blocksPerHour)
        val s = context.getString(if (blocks % blocksPerHour == 0) if (blocks == blocksPerHour) R.string.id_hour else R.string.id_hours else com.greenaddress.greenbits.ui.R.string.id_minutes)
        return String.format(Locale.getDefault(), " ~ %d %s", n, s)
    }

    private fun updateRecipient(index: Int, value: AddressParamsLiveData) {
        while (index >= bindings.size) {
            val recipientBinding = ListItemTransactionRecipientBinding.inflate(layoutInflater)

            recipientBinding.lifecycleOwner = viewLifecycleOwner
            recipientBinding.vm = viewModel

            bindings.add(recipientBinding)
            binding.recipientContainer.addView(recipientBinding.root)

            initRecipientBinging(recipientBinding)
        }

        bindings.getOrNull(index)?.let { binding ->
            updateBindingData(recipientBinding = binding, index = index, liveData = value)
        }
    }

    private fun initRecipientBinging(recipientBinding: ListItemTransactionRecipientBinding) {
        recipientBinding.addressInputLayout.endIconCopyMode()
        recipientBinding.amountTextInputLayout.endIconCopyMode()

        AmountTextWatcher.watch(recipientBinding.amountInputEditText)

        recipientBinding.buttonScan.setOnClickListener {
            viewModel.activeRecipient = recipientBinding.index ?: 0
            CameraBottomSheetDialogFragment.open(this)
        }

        recipientBinding.assetInputEditText.setOnClickListener {
            if(session.isLiquid) {
                // Skip if we have a bip21 asset
                if(viewModel.getRecipientLiveData(recipientBinding.index ?: 0)?.enableAsset?.value == false){
                    return@setOnClickListener
                }

                viewModel.activeRecipient = recipientBinding.index ?: 0

                FilterBottomSheetDialogFragment().also {
                    it.show(childFragmentManager, it.toString())
                }
            }
        }

        recipientBinding.toggleGroupSendAll.addOnButtonCheckedListener { _, _, isChecked ->
            viewModel.sendAll(index = recipientBinding.index ?: 0, isSendAll = isChecked)
        }

        recipientBinding.buttonCurrency.setOnClickListener {
            viewModel.toggleCurrency(index = recipientBinding.index ?: 0)
        }

        recipientBinding.buttonRemove.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.id_remove)
                .setMessage(R.string.id_are_you_sure_you_want_to_remove_the_recipient)
                .setPositiveButton(R.string.id_remove) { _, _ ->
                    viewModel.removeRecipient(recipientBinding.index ?: 0)
                }
                .setNegativeButton(R.string.id_cancel) { _, _ ->

                }
                .show()
        }

        recipientBinding.buttonCoinControl.setOnClickListener {
            viewModel.activeRecipient = recipientBinding.index ?: 0

            // WIP
            SelectUtxosBottomSheetDialogFragment().also {
                it.show(childFragmentManager, it.toString())
            }
        }
    }


    private fun updateBindingData(
        recipientBinding: ListItemTransactionRecipientBinding,
        index: Int,
        liveData: AddressParamsLiveData
    ) {
        recipientBinding.liveData = liveData
        recipientBinding.index = index

        viewModel.getRecipientLiveData(index)?.assetId?.observe(viewLifecycleOwner) { assetId ->
            val balance = viewModel.getAssetsLiveData().value?.firstNotNullOfOrNull { if(it.key == assetId) it.value else null }
            var look : AssetLook? = null

            if(!assetId.isNullOrBlank()) {
                look = AssetLook(
                    id = assetId,
                    amount = balance ?: 0,
                    session = session
                )
            }

            recipientBinding.assetName = look?.name
            recipientBinding.assetBalance = if(look != null) getString(R.string.id_available_funds, look.balance(withUnit = true)) else ""
            recipientBinding.assetSatoshi = balance ?: 0
            setAssetIcon(recipientBinding, assetId)

            recipientBinding.canConvert = assetId == session.network.policyAsset
            recipientBinding.amountTextInputLayout.suffixText = look?.ticker ?: ""
        }

        viewModel.getRecipientLiveData(index)?.isFiat?.observe(viewLifecycleOwner){ isFiat ->
            recipientBinding.amountCurrency = if(isFiat) getFiatCurrency(session) else getBitcoinOrLiquidUnit(session)
            recipientBinding.changeCurrencyTo = if(isFiat) getBitcoinOrLiquidUnit(session) else getFiatCurrency(session)
        }

        // When changing asset and send all is enabled, listen for the event resetting the send all flag
        viewModel.getRecipientLiveData(index)?.isSendAll?.observe(viewLifecycleOwner){ isSendAll ->
            if(recipientBinding.buttonSendAll.isChecked != isSendAll) {
                recipientBinding.buttonSendAll.isChecked = isSendAll
            }
        }
    }

    private fun setAssetIcon(binding: ListItemTransactionRecipientBinding, assetId: String) {

        if(assetId.isBlank()){
            ContextCompat.getDrawable(requireContext(),R.drawable.ic_pending_asset)
        }else{
            assetId.getAssetIcon(requireContext(), session)
        }?.let { drawable ->
            val size = requireContext().toPixels(28)
            val resizedBitmap = drawable.toBitmap(width = size, height = size)
            val resizedDrawable = BitmapDrawable(
                resources,
                Bitmap.createScaledBitmap(resizedBitmap, size, size, true)
            );
            binding.assetInputEditText.setCompoundDrawablesRelativeWithIntrinsicBounds(
                resizedDrawable,
                null,
                null,
                null
            )
        }

    }

    private fun setCustomFeeRate(){
        val dialogBinding = EditTextDialogBinding.inflate(LayoutInflater.from(context))
        dialogBinding.textInputLayout.endIconCopyMode()

        // TODO add locale
        dialogBinding.editText.setPlaceholder("0.00")
        dialogBinding.editText.keyListener = NumberValueFilter(2)
        dialogBinding.text = (viewModel.customFee.toDouble() / 1000).toString()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.id_default_custom_fee_rate)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->

                try {
                    dialogBinding.text.let { input ->
                        if (input.isNullOrBlank()) {
                            viewModel.setCustomFeeRate(null)
                        } else {
                            val minFeeRateKB: Long = viewModel.feeEstimation?.fees?.firstOrNull() ?: session.network.defaultFee
                            val enteredFeeRate = dialogBinding.text?.toDouble() ?: 0.0
                            if (enteredFeeRate * 1000 < minFeeRateKB) {
                                snackbar(
                                    getString(
                                        R.string.id_fee_rate_must_be_at_least_s, String.format(
                                            "%.2f",
                                            minFeeRateKB / 1000.0
                                        )
                                    ), Snackbar.LENGTH_SHORT
                                )
                            } else {
                                viewModel.setCustomFeeRate((enteredFeeRate * 1000).toLong())
                            }
                        }
                    }

                } catch (e: Exception) {
                    snackbar(R.string.id_error_setting_fee_rate, Snackbar.LENGTH_SHORT)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()

    }

    override fun onResume() {
        super.onResume()
        setToolbar(title = getString(if(isSweep) R.string.id_sweep else R.string.id_send))
    }

    override fun onPause() {
        super.onPause()
        hideKeyboard()
    }

    override fun getModelAdapter(): ModelAdapter<*, *> {
        return assetAdapter
    }

    override fun filteredItemClicked(item: GenericItem, position: Int) {
        viewModel.setAsset(viewModel.activeRecipient, (item as AssetListItem).balancePair.first)
    }
}