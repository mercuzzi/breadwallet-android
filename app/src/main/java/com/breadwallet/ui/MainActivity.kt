/**
 * BreadWallet
 *
 * Created by Drew Carlson <drew.carlson@breadwallet.com> on 8/12/19.
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
package com.breadwallet.ui

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.bluelinelabs.conductor.ChangeHandlerFrameLayout
import com.bluelinelabs.conductor.Conductor
import com.bluelinelabs.conductor.Controller
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import com.bluelinelabs.conductor.changehandler.FadeChangeHandler
import com.breadwallet.BuildConfig
import com.breadwallet.R
import com.breadwallet.app.BreadApp
import com.breadwallet.logger.logDebug
import com.breadwallet.logger.logError
import com.breadwallet.protocols.messageexchange.MessageExchangeService
import com.breadwallet.tools.animation.BRDialog
import com.breadwallet.tools.manager.BRSharedPrefs
import com.breadwallet.tools.security.AccountState
import com.breadwallet.tools.security.BRAccountManager
import com.breadwallet.tools.util.EventUtils
import com.breadwallet.tools.util.Link
import com.breadwallet.tools.util.ServerBundlesHelper
import com.breadwallet.tools.util.Utils
import com.breadwallet.tools.util.asLink
import com.breadwallet.ui.disabled.DisabledController
import com.breadwallet.ui.importwallet.ImportController
import com.breadwallet.ui.login.LoginController
import com.breadwallet.ui.migrate.MigrateController
import com.breadwallet.ui.navigation.OnCompleteAction
import com.breadwallet.ui.onboarding.IntroController
import com.breadwallet.ui.pin.InputPinController
import com.breadwallet.ui.recovery.RecoveryKey
import com.breadwallet.ui.recovery.RecoveryKeyController
import com.breadwallet.ui.scanner.ScannerController
import com.breadwallet.ui.send.SendSheetController
import com.breadwallet.ui.web.WebController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.kodein.di.KodeinAware
import org.kodein.di.android.closestKodein
import org.kodein.di.erased.instance

private const val LOCK_TIMEOUT = 180_000L // 3 minutes in milliseconds

// String extra containing a recovery phrase to bootstrap the recovery process. (debug only)
private const val EXTRA_RECOVER_PHRASE = "RECOVER_PHRASE"

/**
 * The main user entrypoint into the app.
 *
 * This activity serves as a Conductor router host and translates
 * platform events into Mobius events.
 */
@Suppress("TooManyFunctions")
class MainActivity : AppCompatActivity(), KodeinAware {

    companion object {
        const val EXTRA_DATA = "com.breadwallet.ui.MainActivity.EXTRA_DATA"
        const val EXTRA_PUSH_NOTIFICATION_CAMPAIGN_ID =
            "com.breadwallet.ui.MainActivity.EXTRA_PUSH_CAMPAIGN_ID"
    }

    override val kodein by closestKodein()

    private val accountManager by instance<BRAccountManager>()

    private lateinit var router: Router

    private val resumedScope = CoroutineScope(Default + SupervisorJob())
    private val pausedScope = CoroutineScope(Default + SupervisorJob())

    private var launchedWithInvalidState = false
    private val isDeviceStateValid: Boolean
        get() = (application as BreadApp).isDeviceStateValid()

    @Suppress("ComplexMethod", "LongMethod")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The view of this activity is nothing more than a Controller host with animation support
        setContentView(ChangeHandlerFrameLayout(this).also { view ->
            router = Conductor.attachRouter(this, view, savedInstanceState)
        })

        if (!isDeviceStateValid) {
            // In this case, isDeviceStateValid displays a dialog (activity)
            // for user resolution of the invalid state. We must check this
            // again in onResume to ensure we display the dialog if the state
            // is unchanged or recreate the activity.
            launchedWithInvalidState = true
            logError("Device state is invalid.")
            return
        }

        accountManager.lockAccount()

        // Allow launching with a phrase to recover automatically
        val hasWallet = accountManager.isAccountInitialized()
        if (BuildConfig.DEBUG && intent.hasExtra(EXTRA_RECOVER_PHRASE) && !hasWallet) {
            val phrase = intent.getStringExtra(EXTRA_RECOVER_PHRASE)
            if (phrase.isNotBlank() && phrase.split(" ").size == RecoveryKey.M.RECOVERY_KEY_WORDS_COUNT) {
                val controller = RecoveryKeyController(RecoveryKey.Mode.RECOVER, phrase)
                router.setRoot(RouterTransaction.with(controller))
                return
            }
        }

        // The app is launched, no screen to be restored
        if (!router.hasRootController()) {
            val rootController = when {
                accountManager.isMigrationRequired() -> MigrateController()
                else -> when (accountManager.getAccountState()) {
                    is AccountState.Disabled -> DisabledController()
                    is AccountState.Uninitialized -> IntroController()
                    else -> if (accountManager.hasPinCode()) {
                        val intentUrl = processIntentData(intent)
                        LoginController(intentUrl)
                    } else {
                        InputPinController(OnCompleteAction.GO_HOME)
                    }
                }
            }
            router.setRoot(
                RouterTransaction.with(rootController)
                    .popChangeHandler(FadeChangeHandler())
                    .pushChangeHandler(FadeChangeHandler())
            )
        }

        if (BuildConfig.DEBUG) {
            Utils.printPhoneSpecs(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        (applicationContext as BreadApp).setDelayServerShutdown(false, -1)
        pausedScope.cancel()
        resumedScope.cancel()
    }

    override fun onResume() {
        super.onResume()
        BreadApp.setBreadContext(this)
        pausedScope.coroutineContext.cancelChildren()

        accountManager.accountStateChanges()
            .map { processAccountState(it) }
            .flowOn(Main)
            .launchIn(resumedScope)

        // If we come back to the activity after launching with
        // an invalid device state, check the state again.
        // If the state is valid, recreate the activity otherwise
        // the resolution dialog will display again.
        if (launchedWithInvalidState) {
            if (isDeviceStateValid) {
                logDebug("Device state is valid, recreating activity.")
                recreate()
            } else {
                logError("Device state is invalid.")
            }
            return
        }
    }

    override fun onPause() {
        super.onPause()
        BreadApp.setBreadContext(null)
        resumedScope.coroutineContext.cancelChildren()
        pausedScope.launch {
            delay(LOCK_TIMEOUT)
            accountManager.lockAccount()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        router.onActivityResult(requestCode, resultCode, data)
        accountManager.onActivityResult(requestCode, resultCode)
    }

    override fun onBackPressed() {
        // Defer to controller back-press control before exiting.
        if (!router.handleBack()) {
            super.onBackPressed()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return checkOverlayAndDispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent ?: return

        val data = processIntentData(intent) ?: ""
        if (data.isNotBlank()) {
            val hasNoRoot = !router.hasRootController()
            val topIsLogin = router.backstack.lastOrNull()?.controller() is LoginController
            val controller = if (hasNoRoot || !topIsLogin) {
                LoginController(data)
            } else {
                data.asLink()?.run(this::handleLink)
            } ?: return

            val transaction = RouterTransaction.with(controller)
                .pushChangeHandler(FadeChangeHandler())
                .popChangeHandler(FadeChangeHandler())

            if (topIsLogin) {
                router.replaceTopController(transaction)
            } else {
                router.pushController(transaction)
            }
        }
    }

    /** Process the new intent and return the url to browse if available */
    private fun processIntentData(intent: Intent): String? {
        if (intent.hasExtra(EXTRA_PUSH_NOTIFICATION_CAMPAIGN_ID)) {
            val campaignId = intent.getStringExtra(EXTRA_PUSH_NOTIFICATION_CAMPAIGN_ID)
            val attributes =
                mapOf<String, String>(EventUtils.EVENT_ATTRIBUTE_CAMPAIGN_ID to campaignId)
            EventUtils.pushEvent(EventUtils.EVENT_MIXPANEL_APP_OPEN, attributes)
            EventUtils.pushEvent(EventUtils.EVENT_PUSH_NOTIFICATION_OPEN)
        }

        val data = intent.getStringExtra(EXTRA_DATA)
        return if (data.isNullOrBlank()) {
            intent.dataString
        } else data
    }

    @Suppress("ComplexMethod")
    private fun handleLink(link: Link): Controller? {
        return when (link) {
            is Link.ImportWallet -> ImportController(
                link.privateKey,
                link.passwordProtected
            )
            is Link.CryptoRequestUrl -> SendSheetController(link)
            is Link.WalletPairUrl -> {
                MessageExchangeService.enqueueWork(
                    applicationContext, MessageExchangeService.createIntent(
                        applicationContext,
                        MessageExchangeService.ACTION_REQUEST_TO_PAIR,
                        link.pairingMetaData
                    )
                )
                null
            }
            is Link.PlatformUrl -> WebController(link.url)
            is Link.PlatformDebugUrl -> {
                if (!link.webBundleUrl.isNullOrBlank()) {
                    ServerBundlesHelper.setWebPlatformDebugURL(this, link.webBundleUrl)
                } else if (!link.webBundle.isNullOrBlank()) {
                    ServerBundlesHelper.setDebugBundle(
                        this,
                        ServerBundlesHelper.Type.WEB,
                        link.webBundle
                    )
                }
                null
            }
            is Link.BreadUrl.ScanQR -> ScannerController()
            is Link.BreadUrl.Address -> null
            is Link.BreadUrl.AddressList -> null
        }
    }

    private fun processAccountState(accountState: AccountState) {
        if (accountManager.isAccountInitialized() && router.hasRootController()) {
            when (accountState) {
                AccountState.Locked -> lockApp()
                is AccountState.Disabled -> disableApp()
            }
        }
    }

    private fun isBackstackDisabled() = router.backstack
        .map(RouterTransaction::controller)
        .filterIsInstance<DisabledController>()
        .any()

    private fun isBackstackLocked() =
        router.backstack.lastOrNull()?.controller()?.let { controller ->
            controller is LoginController || controller is InputPinController
        } ?: false

    private fun disableApp() {
        if (isBackstackDisabled()) return

        logDebug("Disabling backstack.")
        router.pushController(
            RouterTransaction.with(DisabledController())
                .pushChangeHandler(FadeChangeHandler())
                .popChangeHandler(FadeChangeHandler())
        )
    }

    private fun lockApp() {
        if (isBackstackDisabled() || isBackstackLocked()) return

        val controller = when {
            accountManager.hasPinCode() ->
                LoginController(showHome = router.backstackSize == 0)
            else -> InputPinController(
                onComplete = OnCompleteAction.GO_HOME,
                skipWriteDown = BRSharedPrefs.getPhraseWroteDown()
            )
        }

        logDebug("Locking with controller=$controller")

        router.pushController(
            RouterTransaction.with(controller)
                .popChangeHandler(FadeChangeHandler())
                .pushChangeHandler(FadeChangeHandler())
        )
    }

    /**
     * Check if there is an overlay view over the screen, if an
     * overlay view is found the event won't be dispatched and
     * a dialog with a warning will be shown.
     *
     * @param event The touch screen event.
     * @return boolean Return true if this event was consumed or if an overlay view was found.
     */
    private fun checkOverlayAndDispatchTouchEvent(event: MotionEvent): Boolean {
        // Filter obscured touches by consuming them.
        if (event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0) {
            if (event.action == MotionEvent.ACTION_UP) {
                BRDialog.showSimpleDialog(
                    this, getString(R.string.Android_screenAlteringTitle),
                    getString(R.string.Android_screenAlteringMessage)
                )
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }
}
