/*
 * Copyright (c) 2015-2020, Virgil Security, Inc.
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

package com.virgilsecurity.android.ethree.interaction

import android.content.Context
import com.virgilsecurity.android.common.EThreeCore
import com.virgilsecurity.android.common.callback.OnGetTokenCallback
import com.virgilsecurity.android.common.callback.OnKeyChangedCallback
import com.virgilsecurity.android.common.util.Const.NO_CONTEXT
import com.virgilsecurity.android.common.util.Defaults
import com.virgilsecurity.common.model.Result
import com.virgilsecurity.sdk.common.TimeSpan
import com.virgilsecurity.sdk.crypto.KeyPairType
import com.virgilsecurity.sdk.jwt.Jwt
import com.virgilsecurity.sdk.jwt.accessProviders.CachingJwtProvider
import com.virgilsecurity.sdk.storage.DefaultKeyStorage
import com.virgilsecurity.sdk.storage.KeyStorage

/**
 * [EThree] class simplifies work with Virgil Services to easily implement End to End Encrypted
 * communication.
 */
class EThree
@JvmOverloads constructor(
        identity: String,
        tokenCallback: OnGetTokenCallback,
        context: Context,
        keyChangedCallback: OnKeyChangedCallback? = null,
        keyPairType: KeyPairType = Defaults.keyPairType,
        enableRatchet: Boolean = Defaults.enableRatchet,
        keyRotationInterval: TimeSpan = Defaults.keyRotationInterval,
        customServiceKey: String? = null
) : EThreeCore(identity,
               tokenCallback,
               keyChangedCallback,
               keyPairType,
               enableRatchet,
               keyRotationInterval,
               context,
               customServiceKey) {

    override val keyStorage: KeyStorage

    init {
        keyStorage = DefaultKeyStorage(context.filesDir.absolutePath, KEYSTORE_NAME)

        initializeCore()
    }

    constructor(params: com.virgilsecurity.android.common.model.EThreeParams) : this(
        params.identity,
        params.tokenCallback,
        params.context,
        params.keyChangedCallback,
        params.keyPairType,
        params.enableRatchet,
        params.keyRotationInterval,
        params.customCardVerifierServiceKey)

    constructor(params: com.virgilsecurity.android.common.model.java.EThreeParams) : this(
        params.identity,
        params.tokenCallback,
        params.context,
        params.keyChangedCallback,
        params.keyPairType,
        params.enableRatchet,
        params.keyRotationInterval,
        params.customCardVerifierServiceKey)

    @JvmOverloads constructor(
            identity: String,
            tokenStringCallback: () -> String,
            context: Context,
            keyChangedCallback: OnKeyChangedCallback? = null,
            keyPairType: KeyPairType = Defaults.keyPairType,
            enableRatchet: Boolean = Defaults.enableRatchet,
            keyRotationInterval: TimeSpan = Defaults.keyRotationInterval,
            customServiceKey: String? = null
    ) : this(identity,
             object : OnGetTokenCallback {
                 override fun onGetToken(): String {
                     return tokenStringCallback()
                 }

             },
             context,
             keyChangedCallback,
             keyPairType,
             enableRatchet,
             keyRotationInterval,
            customServiceKey)

    companion object {
        /**
         * Current method allows you to initialize EThree helper class. To do this you
         * should provide [onGetTokenCallback] that must return Json Web Token string
         * representation with identity of the user which will use this class.
         * In [onResultListener] you will receive instance of [EThreeCore] class or an [Throwable]
         * if something went wrong.
         *
         * To start execution of the current function, please see [Result] description.
         */
        @JvmStatic
        @JvmOverloads
        @Deprecated("Use constructor instead")
        fun initialize(context: Context,
                       onGetTokenCallback: OnGetTokenCallback,
                       keyChangedCallback: OnKeyChangedCallback? = null) =
                object : Result<EThree> {
                    override fun get(): EThree {
                        val tokenProvider = CachingJwtProvider(CachingJwtProvider.RenewJwtCallback {
                            Jwt(onGetTokenCallback.onGetToken())
                        })

                        // Just check whether we can get token, otherwise there's no reasons to
                        // initialize EThree. We have caching JWT provider, so sequential calls
                        // won't take much time, as token will be cached after first call.
                        val token = tokenProvider.getToken(NO_CONTEXT)
                        val ethree = EThree(token.identity,
                                            onGetTokenCallback,
                                            context,
                                            keyChangedCallback)
                        ethree.initializeCore()

                        return ethree
                    }
                }

        @JvmStatic
        fun derivePasswords(password: String) = derivePasswordsInternal(password)

        private const val KEYSTORE_NAME = "virgil.keystore"
    }
}
