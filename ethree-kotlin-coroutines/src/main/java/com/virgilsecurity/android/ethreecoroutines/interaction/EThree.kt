/*
 * Copyright (c) 2015-2019, Virgil Security, Inc.
 *
 * Lead Maintainer: Virgil Security Inc. <support@virgilsecurity.com>
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     (1) Redistributions of source code must retain the above copyright notice, this
 *     list of conditions and the following disclaimer.
 *
 *     (2) Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *
 *     (3) Neither the name of virgil nor the names of its
 *     contributors may be used to endorse or promote products derived from
 *     this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.virgilsecurity.android.ethreecoroutines.interaction

import android.content.Context
import com.virgilsecurity.android.common.data.local.KeyManagerLocal
import com.virgilsecurity.android.common.data.remote.KeyManagerCloud
import com.virgilsecurity.android.common.exceptions.*
import com.virgilsecurity.android.ethreecoroutines.build.VersionVirgilAgent
import com.virgilsecurity.android.ethreecoroutines.extensions.asyncWithCatch
import com.virgilsecurity.keyknox.exception.DecryptionFailedException
import com.virgilsecurity.keyknox.exception.EntryAlreadyExistsException
import com.virgilsecurity.pythia.brainkey.BrainKey
import com.virgilsecurity.pythia.brainkey.BrainKeyContext
import com.virgilsecurity.pythia.client.VirgilPythiaClient
import com.virgilsecurity.pythia.crypto.VirgilPythiaCrypto
import com.virgilsecurity.sdk.cards.CardManager
import com.virgilsecurity.sdk.cards.validation.VirgilCardVerifier
import com.virgilsecurity.sdk.client.VirgilCardClient
import com.virgilsecurity.sdk.crypto.VirgilCardCrypto
import com.virgilsecurity.sdk.crypto.VirgilCrypto
import com.virgilsecurity.sdk.crypto.VirgilPrivateKey
import com.virgilsecurity.sdk.crypto.VirgilPublicKey
import com.virgilsecurity.sdk.exception.EmptyArgumentException
import com.virgilsecurity.sdk.jwt.Jwt
import com.virgilsecurity.sdk.jwt.accessProviders.CachingJwtProvider
import com.virgilsecurity.sdk.jwt.contract.AccessTokenProvider
import com.virgilsecurity.sdk.storage.DefaultKeyStorage
import com.virgilsecurity.sdk.utils.ConvertionUtils
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async

/**
 * . _  _
 * .| || | _
 * -| || || |   Created by:
 * .| || || |-  Danylo Oliinyk
 * ..\_  || |   on
 * ....|  _/    10/8/18
 * ...-| | \    at Virgil Security
 * ....|_|-
 */

/**
 * [EThree] class simplifies work with Virgil Services to easily implement End to End Encrypted
 * communication.
 */
class EThree
/**
 * @constructor Initializing [CardManager] with provided in [EThree.initialize] callback
 * [onGetTokenCallback] using [CachingJwtProvider] also initializing [DefaultKeyStorage] with
 * default settings.
 */ private constructor(context: Context, private val tokenProvider: AccessTokenProvider) {

    private val virgilCrypto = VirgilCrypto()
    private val cardManager: CardManager
    private val keyManagerLocal: KeyManagerLocal
    private val keyManagerCloud: KeyManagerCloud

    init {
        cardManager = VirgilCardCrypto().let { cardCrypto ->
            CardManager(cardCrypto,
                        tokenProvider,
                        VirgilCardVerifier(cardCrypto, false, false),
                        VirgilCardClient(VIRGIL_BASE_URL + VIRGIL_CARDS_SERVICE_PATH))
        }
        keyManagerLocal = KeyManagerLocal(tokenProvider.getToken(NO_CONTEXT).identity, context)
        keyManagerCloud = KeyManagerCloud(currentIdentity(),
                                          tokenProvider,
                                          VersionVirgilAgent.VERSION)
    }

    /**
     * Publishes the public key in Virgil's Cards Service in case no public key for current
     * identity is published yet. Otherwise [RegistrationException] will be thrown.
     *
     * @throws RegistrationException
     * @throws CryptoException
     */
    @Synchronized fun register(): Deferred<Unit> =
            GlobalScope.async {
                if (cardManager.searchCards(currentIdentity()).isNotEmpty())
                    throw RegistrationException("Card with identity " +
                                                "${currentIdentity()} already exists")

                if (keyManagerLocal.exists())
                    throw PrivateKeyExistsException("You already have a Private Key on this " +
                                                    "device for identity: ${currentIdentity()}. " +
                                                    "Please, use \'cleanup()\' function first.")

                virgilCrypto.generateKeyPair().run {
                    cardManager.publishCard(this.privateKey,
                                            this.publicKey,
                                            currentIdentity())

                    keyManagerLocal.store(virgilCrypto.exportPrivateKey(this.privateKey))
                }
            }

    /**
     * ! *WARNING* ! If you call this function after [register] without using [backupPrivateKey]
     * then you loose private key permanently, as well you won't be able to use identity that
     * was used with that private key no more.
     *
     * Cleans up user's private key from a device - call this function when you want to log your
     * user out of the device.
     *
     * Can be called only if private key is on the device otherwise [PrivateKeyNotFoundException]
     * exception will be thrown.
     *
     * @throws PrivateKeyNotFoundException
     */
    fun cleanup() {
        checkPrivateKeyOrThrow()

        keyManagerLocal.delete()
    }

    /**
     * Checks whether the private key is present in the local storage of current device.
     * Returns *true* if the key is present in the local key storage otherwise *false*.
     */
    fun hasLocalPrivateKey() = keyManagerLocal.exists()

    /**
     * Encrypts the user's private key using the user's [password] and backs up the encrypted
     * private key to Virgil's cloud. This enables users to log in from other devices and have
     * access to their private key to decrypt data.
     *
     * Encrypts loaded from private keys local storage user's *Private key* using *Public key*
     * that is generated based on provided [password] after that backs up encrypted user's
     * *Private key* to the Virgil's cloud storage.
     *
     * Can be called only if private key is on the device otherwise [PrivateKeyNotFoundException]
     * exception will be thrown.
     *
     * @throws PrivateKeyNotFoundException
     * @throws BackupKeyException
     */
    fun backupPrivateKey(password: String): Deferred<Unit> = GlobalScope.asyncWithCatch(
        {
            checkPrivateKeyOrThrow()

            if (password.isBlank())
                throw IllegalArgumentException("\'password\' should not be empty")

            with(keyManagerLocal.load()) {
                keyManagerCloud.store(password,
                                      this.value,
                                      this.meta)
                        .let { Unit }
            }
        },
        {
            if (it is EntryAlreadyExistsException)
                throw BackupKeyException("Key with identity ${currentIdentity()} " +
                                         "already backuped.")
            else
                throw it
        })

    /**
     * Deletes the user's private key from Virgil's cloud.
     *
     * Deletes private key backup using specified [password] and provides [onCompleteListener]
     * callback that will notify you with successful completion or with a [Throwable] if
     * something went wrong.
     *
     * Can be called only if private key is on the device otherwise [PrivateKeyNotFoundException]
     * exception will be thrown.
     *
     * @throws PrivateKeyNotFoundException
     * @throws WrongPasswordException
     */
    fun resetPrivateKeyBackup(password: String? = null): Deferred<Unit> = GlobalScope.asyncWithCatch(
        {
            checkPrivateKeyOrThrow()

            if (password == null) {
                keyManagerCloud.deleteAll()
            } else {
                if (password.isBlank())
                    throw IllegalArgumentException("\'password\' should not be empty")

                keyManagerCloud.delete(password)
            }
        },
        {
            if (it is DecryptionFailedException)
                throw WrongPasswordException("Specified password is not valid.")
            else
                throw it
        })

    /**
     * Pulls user's private key from the Virgil's cloud, decrypts it with *Private key* that
     * is generated based on provided [password] and saves it to the current private keys
     * local storage.
     *
     * @throws WrongPasswordException
     * @throws RestoreKeyException
     */
    fun restorePrivateKey(password: String): Deferred<Unit> = GlobalScope.asyncWithCatch(
        {
            if (keyManagerLocal.exists())
                throw RestoreKeyException("You already have a Private Key on this device" +
                                          "for identity: ${currentIdentity()}. Please, use" +
                                          "\'cleanup()\' function first.")

            if (keyManagerCloud.exists(password)) {

                Thread.sleep(THROTTLE_TIMEOUT) // To avoid next request been throttled

                val keyEntry = keyManagerCloud.retrieve(password)

                keyManagerLocal.store(keyEntry.data)
            } else {
                throw RestoreKeyException("There is no key backup with " +
                                          "identity: ${currentIdentity()}")
            }
        },
        {
            if (it is DecryptionFailedException)
                throw WrongPasswordException("Specified password is not valid.")
            else
                throw it
        })

    /**
     * Generates new key pair, publishes new public key for current identity and deprecating old
     * public key, saves private key to the local storage. All data that was encrypted earlier
     * will become undecryptable.
     *
     * @throws PrivateKeyExistsException
     * @throws CardNotFoundException
     * @throws CryptoException
     */
    fun rotatePrivateKey(): Deferred<Unit> = GlobalScope.async {
        if (keyManagerLocal.exists())
            throw PrivateKeyExistsException("You already have a Private Key on this device" +
                                            "for identity: ${currentIdentity()}. Please, use" +
                                            "\'cleanup()\' function first.")

        val cards = cardManager.searchCards(currentIdentity())
        if (cards.isEmpty())
            throw CardNotFoundException("No cards was found " +
                                        "with identity: ${currentIdentity()}")
        if (cards.size > 1)
            throw IllegalStateException("${cards.size} cards was found " +
                                        "with identity: ${currentIdentity()}. How? (: " +
                                        "Should be <= 1. Please, contact developers if " +
                                        "it was not an intended behaviour.")

        (cards.first() to virgilCrypto.generateKeyPair()).run {
            val rawCard = cardManager.generateRawCard(this.second.privateKey,
                                                      this.second.publicKey,
                                                      currentIdentity(),
                                                      this.first.identifier)
            cardManager.publishCard(rawCard)

            keyManagerLocal.store(this.second.privateKey.privateKey.exportPrivateKey())
        }
    }

    /**
     * Changes the password of the private key backup.
     *
     * Pulls user's private key from the Virgil's cloud storage, decrypts it with *Private key*
     * that is generated based on provided [oldPassword] after that encrypts user's *Private key*
     * using *Public key* that is generated based on provided [newPassword] and pushes encrypted
     * user's *Private key* to the Virgil's cloud storage.
     *
     * Can be called only if private key is on the device otherwise [PrivateKeyNotFoundException]
     * exception will be thrown.
     *
     * @throws PrivateKeyNotFoundException
     */
    fun changePassword(oldPassword: String,
                       newPassword: String): Deferred<Unit> = GlobalScope.async {
        checkPrivateKeyOrThrow()

        if (oldPassword.isBlank())
            throw IllegalArgumentException("\'oldPassword\' should not be empty")
        if (newPassword.isBlank())
            throw IllegalArgumentException("\'newPassword\' should not be empty")
        if (newPassword == oldPassword)
            throw IllegalArgumentException("\'newPassword\' can't be the same as the old one")

        val brainKeyContext = BrainKeyContext.Builder()
                .setAccessTokenProvider(tokenProvider)
                .setPythiaClient(VirgilPythiaClient(VIRGIL_BASE_URL))
                .setPythiaCrypto(VirgilPythiaCrypto())
                .build()

        val keyPair = BrainKey(brainKeyContext).generateKeyPair(newPassword)

        Thread.sleep(THROTTLE_TIMEOUT) // To avoid next request been throttled

        keyManagerCloud.updateRecipients(oldPassword, listOf(keyPair.publicKey), keyPair.privateKey)
    }

    /**
     * Encrypts text messages for a group of users.
     *
     * Encrypts provided [text] using [publicKeys] list of recipients and returns encrypted
     * message converted to *base64* [String].
     *
     * Can be called only if private key is on the device otherwise [PrivateKeyNotFoundException]
     * exception will be thrown.
     *
     * @throws PrivateKeyNotFoundException
     * @throws CryptoException
     */
    fun encrypt(text: String, publicKeys: List<VirgilPublicKey>? = null): String {
        checkPrivateKeyOrThrow()

        if (text.isBlank()) throw EmptyArgumentException("data")
        if (publicKeys?.isEmpty() == true) throw EmptyArgumentException("publicKeys")
        if (publicKeys?.contains(loadCurrentPublicKey()) == true)
            throw IllegalArgumentException("You should not include your own public key.")

        return encrypt(text.toByteArray(), publicKeys).let { ConvertionUtils.toBase64String(it) }
    }

    /**
     * Encrypts messages/other data for a group of users.
     *
     * Encrypts provided [data] using [publicKeys] list of recipients and returns encrypted
     * data as [ByteArray].
     *
     * Can be called only if private key is on the device otherwise [PrivateKeyNotFoundException]
     * exception will be thrown.
     *
     * @throws PrivateKeyNotFoundException
     * @throws CryptoException
     */
    fun encrypt(data: ByteArray, publicKeys: List<VirgilPublicKey>? = null): ByteArray {
        checkPrivateKeyOrThrow()

        if (data.isEmpty()) throw EmptyArgumentException("data")
        if (publicKeys?.isEmpty() == true) throw EmptyArgumentException("publicKeys")
        if (publicKeys?.contains(loadCurrentPublicKey()) == true)
            throw IllegalArgumentException("You should not include your own public key.")

        return (publicKeys == null).let { isNull ->
            (if (isNull) {
                listOf(loadCurrentPublicKey())
            } else {
                publicKeys?.asSequence()?.filterIsInstance<VirgilPublicKey>()?.toMutableList()
                        ?.apply {
                            add(loadCurrentPublicKey())
                        }
            })
        }.let { keys ->
            virgilCrypto.signThenEncrypt(data, loadCurrentPrivateKey(), keys)
        }
    }

    /**
     * Decrypts encrypted text that is in base64 [String] format.
     *
     * Decrypts provided [base64String] (that was previously encrypted with [encrypt] function)
     * using current user's private key.
     *
     * Can be called only if private key is on the device otherwise [PrivateKeyNotFoundException]
     * exception will be thrown.
     *
     * @throws PrivateKeyNotFoundException
     * @throws CryptoException
     */
    fun decrypt(base64String: String, sendersKey: VirgilPublicKey? = null): String {
        checkPrivateKeyOrThrow()

        if (base64String.isBlank()) throw EmptyArgumentException("data")
        if (sendersKey == loadCurrentPublicKey())
            throw IllegalArgumentException("You should not provide your own public key.")

        return String(decrypt(ConvertionUtils.base64ToBytes(base64String), sendersKey))
    }

    /**
     * Decrypts encrypted data.
     *
     * Decrypts provided [data] using current user's private key and returns decrypted data
     * in [ByteArray].
     *
     * Can be called only if private key is on the device otherwise [PrivateKeyNotFoundException]
     * exception will be thrown.
     *
     * @throws PrivateKeyNotFoundException
     * @throws CryptoException
     */
    fun decrypt(data: ByteArray, sendersKey: VirgilPublicKey? = null): ByteArray {
        checkPrivateKeyOrThrow()

        if (data.isEmpty()) throw EmptyArgumentException("data")
        if (sendersKey == loadCurrentPublicKey())
            throw IllegalArgumentException("You should not provide your own public key.")

        return (sendersKey == null).let { isNull ->
            (if (isNull) {
                listOf(loadCurrentPublicKey())
            } else {
                mutableListOf(sendersKey as VirgilPublicKey).apply {
                    add(loadCurrentPublicKey())
                }
            })
        }.let { keys ->
            virgilCrypto.decryptThenVerify(
                data,
                loadCurrentPrivateKey(),
                keys
            )
        }
    }

    /**
     * Retrieves user public keys from the cloud for encryption/verification operations.
     *
     * Searches for public keys with specified [identities] and returns list of [PublicKey] in
     * [onResultListener] callback or [Throwable] if something went wrong.
     *
     * [PublicKeyNotFoundException] will be thrown for the first not found public key.
     *
     * Can be called only if private key is on the device otherwise [PrivateKeyNotFoundException]
     * exception will be thrown.
     *
     * @throws PrivateKeyNotFoundException
     * @throws PublicKeyDuplicateException
     */
    fun lookupPublicKeys(identities: List<String>): Deferred<Map<String, VirgilPublicKey>> = // TODO check arrayOf and [] with collections to not write listof()
            GlobalScope.async {
                if (identities.isEmpty()) throw EmptyArgumentException("identities")

                identities.groupingBy { it }
                        .eachCount()
                        .filter { it.value > 1 }
                        .run {
                            if (this.isNotEmpty())
                                throw PublicKeyDuplicateException("Duplicates are not allowed. " +
                                                                  "Duplicated identities:\n${this}")
                        }

                identities.map {
                    cardManager.searchCards(it) to it
                }.map {
                    if (it.first.size > 1)
                        throw IllegalStateException("${it.first.size} cards was found  with " +
                                                    "identity: ${currentIdentity()}. How? (: " +
                                                    "Should be <= 1. Please, contact developers " +
                                                    "if it was not an intended behaviour.")
                    else
                        it
                }.map {
                    it.second to it.first
                }.map {
                    if (it.second.isNotEmpty())
                        it.first to it.second.first().publicKey as VirgilPublicKey
                    else
                        throw PublicKeyNotFoundException(it.first)
                }.toMap()
            }

    /**
     * Loads and returns current user's [PrivateKey]. Current user's identity is taken
     * from [tokenProvider].
     */
    private fun loadCurrentPrivateKey(): VirgilPrivateKey =
            keyManagerLocal.load().let {
                virgilCrypto.importPrivateKey(it.value).privateKey
            }

    /**
     * Loads and returns current user's [PublicKey] that is extracted from current
     * user's [PrivateKey]. Current user's identity is taken from [tokenProvider].
     */
    private fun loadCurrentPublicKey(): VirgilPublicKey =
            virgilCrypto.extractPublicKey(loadCurrentPrivateKey())

    /**
     * Extracts current user's *Identity* from Json Web Token received from [tokenProvider].
     */
    private fun currentIdentity() = tokenProvider.getToken(NO_CONTEXT).identity

    /**
     * Checks if private key for current identity is present in local key storage or throws an
     * [PrivateKeyNotFoundException] exception.
     */
    private fun checkPrivateKeyOrThrow() {
        if (!keyManagerLocal.exists())
            throw PrivateKeyNotFoundException("You have to get private key first. Use " +
                                              "\'register\' or \'restorePrivateKey\' functions.")
    }

    companion object {
        /**
         * Current method allows you to initialize EThree helper class. To do this you should
         * provide [onGetToken] function that must return Json Web Token string representation
         * with identity of the user which will use this class. You will receive instance of
         * [EThree] class or an [Throwable] if something went wrong.
         */
        @JvmStatic fun initialize(context: Context,
                                  onGetToken: () -> String): Deferred<EThree> {
            val tokenProvider = CachingJwtProvider(CachingJwtProvider.RenewJwtCallback {
                Jwt(onGetToken())
            })

            return GlobalScope.async {
                if (tokenProvider.getToken(NO_CONTEXT) != null)
                    EThree(context,
                           tokenProvider)
                else
                    throw IllegalStateException("Token is null after successful parsing")
            }
        }


        private const val VIRGIL_BASE_URL = "https://api.virgilsecurity.com"
        private const val VIRGIL_CARDS_SERVICE_PATH = "/card/v5/"

        private const val THROTTLE_TIMEOUT = 2 * 1000L // 2 seconds
        private val NO_CONTEXT = null
    }
}
