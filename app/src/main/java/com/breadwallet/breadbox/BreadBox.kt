/**
 * BreadWallet
 *
 * Created by Drew Carlson <drew.carlson@breadwallet.com> on 9/10/19.
 * Copyright (c) 2019 breadwallet LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.breadwallet.breadbox

import com.breadwallet.crypto.Account

import com.breadwallet.crypto.Wallet
import com.breadwallet.crypto.WalletManagerMode
import com.breadwallet.crypto.System
import com.breadwallet.crypto.Transfer
import kotlinx.coroutines.flow.Flow
import java.io.File

/** Provides access to data from a lazily created [System] using [Flow]s. */
@Suppress("TooManyFunctions")
interface BreadBox {

    companion object {
        /** Create an instance of [CoreBreadBox]. */
        fun create(
            storageFile: File,
            isMainnet: Boolean = false,
            walletManagerMode: WalletManagerMode = WalletManagerMode.API_ONLY
        ): BreadBox = CoreBreadBox(
            storageFile,
            isMainnet,
            walletManagerMode
        )
    }

    /** True when all systems are expected to be running and a [System] is available. */
    val isOpen: Boolean

    /** Create and configure [System] and start receiving events. */
    fun open(account: Account)

    /** Cleanup [System] and stop emitting events. */
    fun close()

    /** Final clean-up: cancel flows. */
    fun empty()

    /** Emits the [System] objects produced when calling [open]. */
    fun system(): Flow<System>

    /** Emits the [Account] provided to [open]. */
    fun account(): Flow<Account>

    /** Emits the [Wallet]s tracked by the [System]. */
    fun wallets(): Flow<List<Wallet>>

    /** Emits the list of tracked currency codes. */
    fun currencyCodes(): Flow<List<String>>

    /** Emits the [Wallet] for [currencyCode]. */
    fun wallet(currencyCode: String): Flow<Wallet>

    /** Emits the [WalletSyncState] for the [Wallet] of [currencyCode]. */
    fun walletSyncState(currencyCode: String): Flow<WalletSyncState>

    /** Emits the [List] of [Transfer]s for the [Wallet] of [currencyCode]. */
    fun walletTransfers(currencyCode: String): Flow<List<Transfer>>

    /** Returns [System] when [isOpen] or null when it is not. */
    fun getSystemUnsafe(): System?
}