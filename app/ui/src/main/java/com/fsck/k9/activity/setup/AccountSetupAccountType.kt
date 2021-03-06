package com.fsck.k9.activity.setup

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import com.fsck.k9.Account
import com.fsck.k9.Preferences
import com.fsck.k9.activity.K9Activity
import com.fsck.k9.helper.EmailHelper.getDomainFromEmailAddress
import com.fsck.k9.preferences.Protocols
import com.fsck.k9.setup.ServerNameSuggester
import com.fsck.k9.ui.R
import org.koin.android.ext.android.inject
import java.net.URI

/**
 * Prompts the user to select an account type. The account type, along with the
 * passed in email address, password and makeDefault are then passed on to the
 * AccountSetupIncoming activity.
 */
class AccountSetupAccountType : K9Activity() {
    private val preferences: Preferences by inject()
    private val serverNameSuggester: ServerNameSuggester by inject()

    private lateinit var account: Account
    private var makeDefault = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setLayout(R.layout.account_setup_account_type)

        decodeArguments()

        findViewById<View>(R.id.pop).setOnClickListener { setupPop3Account() }
        findViewById<View>(R.id.imap).setOnClickListener { setupImapAccount() }
    }

    private fun decodeArguments() {
        val accountUuid = intent.getStringExtra(EXTRA_ACCOUNT) ?: error("No account UUID provided")
        val domain = intent.getStringExtra(EXTRA_DOMAIN)
        account = preferences.getAccount(accountUuid) ?: error("No account with given UUID found")
        makeDefault = intent.getBooleanExtra(EXTRA_MAKE_DEFAULT, false)
        if (domain.equals("qq.com")) {
            findViewById<View>(R.id.pop).visibility = View.GONE
            findViewById<View>(R.id.imap).visibility = View.VISIBLE
        } else if (domain.equals("163.com")) {
            findViewById<View>(R.id.imap).visibility = View.GONE
            findViewById<View>(R.id.pop).visibility = View.VISIBLE
        } else if (domain.equals("126.com")) {
            findViewById<View>(R.id.imap).visibility = View.GONE
            findViewById<View>(R.id.pop).visibility = View.VISIBLE
        } else {
            findViewById<View>(R.id.pop).visibility = View.VISIBLE
            findViewById<View>(R.id.imap).visibility = View.GONE
        }

    }

    private fun setupPop3Account() {
        setupAccount(Protocols.POP3, "pop3+ssl+")
    }

    private fun setupImapAccount() {
        setupAccount(Protocols.IMAP, "imap+ssl+")
    }

    private fun setupAccount(serverType: String, schemePrefix: String) {
        setupStoreAndSmtpTransport(serverType, schemePrefix)
        returnAccountTypeSelectionResult()
    }

    private fun setupStoreAndSmtpTransport(serverType: String, schemePrefix: String) {
        val domainPart = getDomainFromEmailAddress(account.email)
            ?: error("Couldn't get domain from email address")

        setupStoreUri(serverType, domainPart, schemePrefix)
        setupTransportUri(domainPart)
    }

    private fun setupStoreUri(serverType: String, domainPart: String, schemePrefix: String) {
        val suggestedStoreServerName = serverNameSuggester.suggestServerName(serverType, domainPart)
        val storeUriForDecode = URI(account.storeUri)
        val storeUri = URI(
            schemePrefix, storeUriForDecode.userInfo, suggestedStoreServerName,
            storeUriForDecode.port, null, null, null
        )
        account.storeUri = storeUri.toString()
    }

    private fun setupTransportUri(domainPart: String) {
        val suggestedTransportServerName =
            serverNameSuggester.suggestServerName(Protocols.SMTP, domainPart)
        val transportUriForDecode = URI(account.transportUri)
        val transportUri = URI(
            "smtp+tls+", transportUriForDecode.userInfo, suggestedTransportServerName,
            transportUriForDecode.port, null, null, null
        )
        account.transportUri = transportUri.toString()
    }

    private fun returnAccountTypeSelectionResult() {
        AccountSetupIncoming.actionIncomingSettings(this, account, makeDefault)
        finish()
    }
//    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
//        return (event.action == KeyEvent.ACTION_DOWN
//            && KeyEvent.KEYCODE_BACK == keyCode)
//    }
    companion object {
        private const val EXTRA_ACCOUNT = "account"
        private const val EXTRA_MAKE_DEFAULT = "makeDefault"
        private const val EXTRA_DOMAIN = "domain"
        @JvmStatic
        fun actionSelectAccountType(context: Context, account: Account, makeDefault: Boolean, domain: String) {
            val intent = Intent(context, AccountSetupAccountType::class.java).apply {
                putExtra(EXTRA_ACCOUNT, account.uuid)
                putExtra(EXTRA_MAKE_DEFAULT, makeDefault)
                putExtra(EXTRA_DOMAIN, domain)
            }
            context.startActivity(intent)
        }
    }
}
