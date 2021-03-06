package com.fsck.k9.ui.settings

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.fsck.k9.Account
import com.fsck.k9.account.BackgroundAccountRemover
import com.fsck.k9.fragment.ConfirmationDialogFragment
import com.fsck.k9.ui.R
import com.fsck.k9.ui.observeNotNull
import com.fsck.k9.ui.settings.account.AccountSettingsActivity
import com.fsck.k9.ui.settings.account.AccountSettingsFragment
import com.fsck.k9.util.UpdateUtil
import com.xwray.groupie.GroupAdapter
import com.xwray.groupie.Item
import com.xwray.groupie.Section
import com.xwray.groupie.kotlinandroidextensions.ViewHolder
import kotlinx.android.synthetic.main.fragment_settings_list.*
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.ArrayList

class SettingsListFragment : Fragment(), ConfirmationDialogFragment.ConfirmationDialogFragmentListener {
    private val viewModel: SettingsViewModel by viewModel()
    private lateinit var accountSetting: AccountSettingsFragment
    private lateinit var settingsAdapter: GroupAdapter<ViewHolder>
    private val accountRemover: BackgroundAccountRemover by inject()
    private val mAccounts: MutableList<Account> = mutableListOf()
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = view.context
        var updateUtil = UpdateUtil(context)
        var task = updateUtil.CheckApkTask1()
        task.execute()
        initializeSettingsList()
        populateSettingsList()
    }

    private fun initializeSettingsList() {
        accountSetting = AccountSettingsFragment()
        settingsAdapter = GroupAdapter()
        settingsAdapter.setOnItemClickListener { item, _ ->
            handleItemClick(item)
        }

        with(settings_list) {
            adapter = settingsAdapter
            layoutManager = LinearLayoutManager(context)
        }
    }

    private fun populateSettingsList() {
        viewModel.accounts.observeNotNull(this) { accounts ->
            //如果用户为空就直接跳到首页面
            if (accounts.isEmpty()) {
                launchOnboarding()
            } else {
//                Toast.makeText(context, "看看有几个..1111" + accounts.size, Toast.LENGTH_SHORT).show()
                mAccounts.clear()
                for (account:Account in accounts) {
                    val name = account.name
                    Log.i("tag", "cccccccccc$name")
                    if (account.toString() == null || name == "") {
                        accountRemover.removeAccountAsync(account.uuid)
                    }
                    if (account.description != null) {
                        mAccounts.add(account)
                    }
                }
                Log.i("tag","BBBBBBBBccccccc"+mAccounts.size)
                populateSettingsList(mAccounts)
            }
        }
    }

    private fun populateSettingsList(accounts: List<Account>) {
        settingsAdapter.clear()
//        Toast.makeText(context, "看看有几个.." + accounts.size, Toast.LENGTH_SHORT).show()
        Log.i("tag","BBBBBBBBcccccccddd"+mAccounts.size)
//        val generalSection = Section().apply {
//            val generalSettingsActionItem = SettingsActionItem(
//                    getString(R.string.general_settings_title),
//                    R.id.action_settingsListScreen_to_generalSettingsScreen,
//                    R.attr.iconSettingsGeneral
//            )
//            add(generalSettingsActionItem)
//        }
//        settingsAdapter.add(generalSection)
        val accountSection = Section().apply {
            for (account in accounts) {
                if (account.description != null) {
                    add(AccountItem(account, context, accountSetting, this@SettingsListFragment))
                }
            }

            val addAccountActionItem = SettingsActionItem(
                getString(R.string.add_account_action),
                R.id.action_settingsListScreen_to_addAccountScreen,
                R.attr.iconSettingsAccountAdd,
                context
            )
            add(addAccountActionItem)
        }
        accountSection.setHeader(SettingsDividerItem(getString(R.string.accounts_title)))
        settingsAdapter.add(accountSection)

//        val backupSection = Section().apply {
//            val exportSettingsActionItem = SettingsActionItem(
//                    getString(R.string.settings_export_title),
//                    R.id.action_settingsListScreen_to_settingsExportScreen,
//                    R.attr.iconSettingsExport
//            )
//            add(exportSettingsActionItem)
//
//            val importSettingsActionItem = SettingsActionItem(
//                    getString(R.string.settings_import_title),
//                    R.id.action_settingsListScreen_to_settingsImportScreen,
//                    R.attr.iconSettingsImport
//            )
//            add(importSettingsActionItem)
//        }
//        backupSection.setHeader(SettingsDividerItem(getString(R.string.settings_list_backup_category)))
//        settingsAdapter.add(backupSection)

        val miscSection = Section().apply {
            val accountActionItem = SettingsActionItem(
                getString(R.string.about_action),
                R.id.action_settingsListScreen_to_aboutScreen,
                R.attr.iconSettingsAbout,
                context
            )
            add(accountActionItem)
        }
        miscSection.setHeader(SettingsDividerItem(getString(R.string.settings_list_miscellaneous_category)))
        settingsAdapter.add(miscSection)
    }

    /**
     * item 点击事件
     */
    //Todo   在这加更新操作
    private fun handleItemClick(item: Item<*>) {
        when (item) {
//            is AccountItem -> launchAccountSettings(item.account)
            is SettingsActionItem -> findNavController().navigate(item.navigationAction)
        }
    }

    private fun launchAccountSettings(account: Account) {
        AccountSettingsActivity.start(requireActivity(), account.uuid)
    }

    private fun launchOnboarding() {
        findNavController().navigate(R.id.action_settingsListScreen_to_onboardingScreen)
        requireActivity().finish()
    }

    override fun dialogCancelled(dialogId: Int,account: Account) {
    }

    override fun doPositiveClick(dialogId: Int,account: Account) {
//        val sp =
//            activity!!.getSharedPreferences("state", Context.MODE_PRIVATE)
//        sp.edit().putInt("count", 1).commit()
        accountRemover.removeAccountAsync(account.uuid)

//        closeAccountSettings()
    }

    override fun doNegativeClick(dialogId: Int,account: Account) {
    }
}
