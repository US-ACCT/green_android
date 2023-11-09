package com.blockstream.common.gdk

import com.blockstream.common.gdk.Wally.Companion.BIP39_WORD_LIST_LANG
import com.blockstream.common.utils.getSecureRandom
import com.blockstream.common.utils.toHex
import com.blockstream.libwally.Wally as WallyJNI

inline fun <T, R> T.bip32KeyFree(use: (T) -> R): R {
    try {
        return use(this)
    } catch (e: Throwable) {
        throw e
    } finally {
        WallyJNI.bip32_key_free(this)
    }
}

class AndroidWally: Wally {
    override val aesBlockLen: Int = WallyJNI.AES_BLOCK_LEN
    override val hmacSha256Len: Int = WallyJNI.HMAC_SHA256_LEN
    override val ecPrivateKeyLen: Int = WallyJNI.EC_PRIVATE_KEY_LEN
    override val bip39TotalWords: Int = WallyJNI.BIP39_WORDLIST_LEN

    private val bip39WordList by lazy { WallyJNI.bip39_get_wordlist(BIP39_WORD_LIST_LANG) }

    init {
        WallyJNI.init(0)
        WallyJNI.secp_randomize(getSecureRandom().randomBytes(WallyJNI.WALLY_SECP_RANDOMIZE_LEN))
    }

    override fun ecPrivateKeyVerify(privateKey: ByteArray): Boolean = try {
        WallyJNI.ec_private_key_verify(privateKey)
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    override fun bip39GetWord(index: Int): String {
        return WallyJNI.bip39_get_word(bip39WordList, index.toLong())
    }

    override fun bip39MnemonicValidate(mnemonic: String): Boolean {
        return try {
            WallyJNI.bip39_mnemonic_validate(bip39WordList, mnemonic)
            true
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            false
        }
    }

    override fun isXpubValid(xpub: String): Boolean {
        try {
            WallyJNI.bip32_key_from_base58(xpub)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    override fun bip32Fingerprint(bip32xPub: String): String? {
        return try {
            val bip32Key = WallyJNI.bip32_key_from_base58(bip32xPub)
            return WallyJNI.bip32_key_get_fingerprint(bip32Key).toHex()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun bip85FromMnemonic(
        mnemonic: String,
        passphrase: String?,
        isTestnet: Boolean,
        index: Long,
        numOfWords: Long
    ): String? {
        return try {
            val seed512 = WallyJNI.bip39_mnemonic_to_seed512(mnemonic, passphrase)
            val version =
                if (isTestnet) WallyJNI.BIP32_VER_TEST_PRIVATE else WallyJNI.BIP32_VER_MAIN_PRIVATE

            val bip32Key = WallyJNI.bip32_key_from_seed(
                seed512,
                version.toLong(),
                WallyJNI.BIP32_FLAG_SKIP_HASH.toLong()
            )

            val bip85 = WallyJNI.bip85_get_bip39_entropy(
                bip32Key,
                BIP39_WORD_LIST_LANG,
                numOfWords,
                index
            )

            WallyJNI.bip39_mnemonic_from_bytes(bip39WordList, bip85)

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun bip85FromJade(
        privateKey: ByteArray,
        publicKey: ByteArray,
        label: String,
        payload: ByteArray
    ): String? {
        return try {
            val out = ByteArray(payload.size)

            val written = WallyJNI.aes_cbc_with_ecdh_key(
                privateKey,
                null,
                payload,
                publicKey,
                label.encodeToByteArray(),
                WallyJNI.AES_FLAG_DECRYPT.toLong(),
                out
            )

            out.slice(0 until written).toByteArray().let {
                WallyJNI.bip39_mnemonic_from_bytes(bip39WordList, it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

actual fun getWally(): Wally = AndroidWally()