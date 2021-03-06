
package com.fsck.k9.activity.setup;


import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.TextKeyListener;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import com.fsck.k9.Account;
import com.fsck.k9.Core;
import com.fsck.k9.Preferences;
import com.fsck.k9.activity.MessageList;
import com.fsck.k9.helper.Utility;
import com.fsck.k9.ui.R;
import com.fsck.k9.activity.K9Activity;
import com.fsck.k9.util.NetworkUtils;


public class AccountSetupOptions extends K9Activity implements OnClickListener {
    private static final String EXTRA_ACCOUNT = "account";

    private static final String EXTRA_MAKE_DEFAULT = "makeDefault";

    private Spinner mCheckFrequencyView;

    private Spinner mDisplayCountView;


    private CheckBox mNotifyView;

    private Account mAccount;


    private EditText mDescription;

    private EditText mName;


    private Button mDoneButton;


    public static void actionOptions(Context context, Account account, boolean makeDefault) {
        Intent i = new Intent(context, AccountSetupOptions.class);
        i.putExtra(EXTRA_ACCOUNT, account.getUuid());
        i.putExtra(EXTRA_MAKE_DEFAULT, makeDefault);
        context.startActivity(i);
    }

    //=======
    private void validateFields() {
        mDoneButton.setEnabled(Utility.requiredFieldValid(mName));
        Utility.setCompoundDrawablesAlpha(mDoneButton, mDoneButton.isEnabled() ? 255 : 128);
    }

    //=======
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setLayout(R.layout.account_setup_options);
//=========
        mDescription = findViewById(R.id.account_description);
        mName = findViewById(R.id.account_name);
        mDoneButton = findViewById(R.id.done);
        mDoneButton.setOnClickListener(this);

        TextWatcher validationTextWatcher = new TextWatcher() {
            public void afterTextChanged(Editable s) {
                validateFields();
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        };
        mName.addTextChangedListener(validationTextWatcher);

        mName.setKeyListener(TextKeyListener.getInstance(false, TextKeyListener.Capitalize.WORDS));
        //=================
        mCheckFrequencyView = findViewById(R.id.account_check_frequency);
        mDisplayCountView = findViewById(R.id.account_display_count);
        mNotifyView = findViewById(R.id.account_notify);

//        findViewById(R.id.back).setVisibility(View.GONE);
//        findViewById(R.id.back1).setVisibility(View.VISIBLE);
        SpinnerOption checkFrequencies[] = {
//            new SpinnerOption(-1,
//            getString(R.string.account_setup_options_mail_check_frequency_never)),
//            new SpinnerOption(15,
//            getString(R.string.account_setup_options_mail_check_frequency_15min)),
//            new SpinnerOption(30,
//            getString(R.string.account_setup_options_mail_check_frequency_30min)),
//            new SpinnerOption(60,
//            getString(R.string.account_setup_options_mail_check_frequency_1hour)),
//            new SpinnerOption(120,
//            getString(R.string.account_setup_options_mail_check_frequency_2hour)),
//            new SpinnerOption(180,
//            getString(R.string.account_setup_options_mail_check_frequency_3hour)),
//            new SpinnerOption(360,
//            getString(R.string.account_setup_options_mail_check_frequency_6hour)),
//            new SpinnerOption(720,
//            getString(R.string.account_setup_options_mail_check_frequency_12hour)),
//            new SpinnerOption(1440,
//            getString(R.string.account_setup_options_mail_check_frequency_24hour)),
                new SpinnerOption(10,
                        getString(R.string.account_setup_options_mail_check_frequency_1hour))

        };

        ArrayAdapter<SpinnerOption> checkFrequenciesAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, checkFrequencies);
        checkFrequenciesAdapter
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mCheckFrequencyView.setAdapter(checkFrequenciesAdapter);

        SpinnerOption displayCounts[] = {
//            new SpinnerOption(10, getString(R.string.account_setup_options_mail_display_count_10)),
                new SpinnerOption(80, getString(R.string.account_setup_options_mail_display_count_25)),
//            new SpinnerOption(50, getString(R.string.account_setup_options_mail_display_count_50)),
//            new SpinnerOption(100, getString(R.string.account_setup_options_mail_display_count_100)),
//            new SpinnerOption(250, getString(R.string.account_setup_options_mail_display_count_250)),
//            new SpinnerOption(500, getString(R.string.account_setup_options_mail_display_count_500)),
//            new SpinnerOption(1000, getString(R.string.account_setup_options_mail_display_count_1000)),
        };

        ArrayAdapter<SpinnerOption> displayCountsAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, displayCounts);
        displayCountsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mDisplayCountView.setAdapter(displayCountsAdapter);

        String accountUuid = getIntent().getStringExtra(EXTRA_ACCOUNT);
        mAccount = Preferences.getPreferences(this).getAccount(accountUuid);

        mNotifyView.setChecked(mAccount.isNotifyNewMail());
        SpinnerOption.setSpinnerOptionValue(mCheckFrequencyView, mAccount
                .getAutomaticCheckIntervalMinutes());
        SpinnerOption.setSpinnerOptionValue(mDisplayCountView, mAccount
                .getDisplayCount());


        //===============
        if (mAccount.getName() != null) {
            mName.setText(mAccount.getName());
        }
//        else{
//            mName.setText(mAccount.getEmail());
//        }
        if (!Utility.requiredFieldValid(mName)) {
            mDoneButton.setEnabled(false);
        }
        //===================
    }

    private void onDone() {
        mAccount.setDescription(mAccount.getEmail());
        mAccount.setNotifyNewMail(mNotifyView.isChecked());
        mAccount.setAutomaticCheckIntervalMinutes((Integer) ((SpinnerOption) mCheckFrequencyView
                .getSelectedItem()).value);
        mAccount.setDisplayCount((Integer) ((SpinnerOption) mDisplayCountView
                .getSelectedItem()).value);

        mAccount.setFolderPushMode(Account.FolderMode.NONE);
        //========================
        if (Utility.requiredFieldValid(mDescription)) {
            mAccount.setDescription(mDescription.getText().toString());
        }
        String s = mName.getText().toString();
        Log.i("tag", "cccccccccc1111" + s);
        mAccount.setName(mName.getText().toString());
        //=======================
        Preferences.getPreferences(getApplicationContext()).saveAccount(mAccount);
        if (mAccount.equals(Preferences.getPreferences(this).getDefaultAccount()) ||
                getIntent().getBooleanExtra(EXTRA_MAKE_DEFAULT, false)) {
            Preferences.getPreferences(this).setDefaultAccount(mAccount);
            Log.i("tag", "cccccccccc1111222222");
        }
        Core.setServicesEnabled(this);
//        AccountSetupNames.actionSetNames(this, mAccount);
        MessageList.launch(this);
        finish();
    }

    public void onClick(View v) {
        if (v.getId() == R.id.done) {

            if (NetworkUtils.isNetWorkAvailable(AccountSetupOptions.this)) {
                onDone();
            } else {
                Toast.makeText(AccountSetupOptions.this, "请连接网络", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
