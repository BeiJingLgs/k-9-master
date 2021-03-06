package com.fsck.k9.fragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Future;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fsck.k9.Account;
import com.fsck.k9.Account.SortType;
import com.fsck.k9.DI;
import com.fsck.k9.K9;
import com.fsck.k9.MyApplication;
import com.fsck.k9.Preferences;
import com.fsck.k9.controller.SimpleMessagingListener;
import com.fsck.k9.db.FujianBeanDB;
import com.fsck.k9.ui.choosefolder.ChooseFolderActivity;
import com.fsck.k9.activity.FolderInfoHolder;
import com.fsck.k9.activity.misc.ContactPicture;
import com.fsck.k9.cache.EmailProviderCache;
import com.fsck.k9.controller.MessageReference;
import com.fsck.k9.controller.MessagingController;
import com.fsck.k9.fragment.ConfirmationDialogFragment.ConfirmationDialogFragmentListener;
import com.fsck.k9.helper.Utility;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mailstore.LocalFolder;
import com.fsck.k9.preferences.StorageEditor;
import com.fsck.k9.search.LocalSearch;
import com.fsck.k9.ui.R;
import com.fsck.k9.ui.folders.FolderNameFormatter;
import com.fsck.k9.ui.folders.FolderNameFormatterFactory;
import com.fsck.k9.ui.messagelist.MessageListAppearance;
import com.fsck.k9.ui.messagelist.MessageListConfig;
import com.fsck.k9.ui.messagelist.MessageListFragmentDiContainer;
import com.fsck.k9.ui.messagelist.MessageListInfo;
import com.fsck.k9.ui.messagelist.MessageListItem;
import com.fsck.k9.ui.messagelist.MessageListViewModel;
import com.fsck.k9.util.InterfaceAdapter;
import com.fsck.k9.util.NetworkUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.jcip.annotations.GuardedBy;

import timber.log.Timber;

import static com.fsck.k9.Account.Expunge.EXPUNGE_MANUALLY;
import static com.fsck.k9.fragment.MessageListFragment.MessageListFragmentListener.MAX_PROGRESS;
import static com.fsck.k9.search.LocalSearchExtensions.getAccountsFromLocalSearch;


public class MessageListFragment extends Fragment implements OnItemClickListener, View.OnClickListener,
        ConfirmationDialogFragmentListener, MessageListItemActionListener {

    private RecyclerView mRecyclerView;
    private TextView left_message_list;
    private TextView right_message_list;
    private TextView limit_count;

    private LinearLayout ll_bar;
    private TextView tv_loading;
    private SharedPreferences sp_list;
    private String messageJson;
    private List<MessageListItem> oList;
    private int oListCount;
    private Boolean isClickItem = false;
    private List<MessageListItem> messageListItems;
    private int limit_num;

    public static MessageListFragment newInstance(
            LocalSearch search, boolean isThreadDisplay, boolean threadedList) {
        MessageListFragment fragment = new MessageListFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARG_SEARCH, search);
        args.putBoolean(ARG_IS_THREAD_DISPLAY, isThreadDisplay);
        args.putBoolean(ARG_THREADED_LIST, threadedList);
        fragment.setArguments(args);
        return fragment;
    }

    private static final int ACTIVITY_CHOOSE_FOLDER_MOVE = 1;
    private static final int ACTIVITY_CHOOSE_FOLDER_COPY = 2;

    private static final String ARG_SEARCH = "searchObject";
    private static final String ARG_THREADED_LIST = "showingThreadedList";
    private static final String ARG_IS_THREAD_DISPLAY = "isThreadedDisplay";

    private static final String STATE_SELECTED_MESSAGES = "selectedMessages";
    private static final String STATE_ACTIVE_MESSAGE = "activeMessage";
    private static final String STATE_REMOTE_SEARCH_PERFORMED = "remoteSearchPerformed";
    private static final String STATE_MESSAGE_LIST = "listState";

    private final SortTypeToastProvider sortTypeToastProvider = DI.get(SortTypeToastProvider.class);
    private final MessageListFragmentDiContainer diContainer = new MessageListFragmentDiContainer(this);
    private final FolderNameFormatterFactory folderNameFormatterFactory = DI.get(FolderNameFormatterFactory.class);
    private FolderNameFormatter folderNameFormatter;

    ListView listView;
    private SwipeRefreshLayout swipeRefreshLayout;
    Parcelable savedListState;

    private MessageListAdapter adapter;
    private boolean messageListLoaded;
    private View footerView;
    private FolderInfoHolder currentFolder;
    private LayoutInflater layoutInflater;
    private MessagingController messagingController;
    private Account account;
    private String[] accountUuids;

    /**
     * Stores the server ID of the folder that we want to open as soon as possible after load.
     */
    private String folderServerId;
    private boolean remoteSearchPerformed = false;
    private Future<?> remoteSearchFuture = null;
    private List<String> extraSearchResults;

    private String title;
    private LocalSearch search = null;
    private boolean singleAccountMode;
    private boolean singleFolderMode;
    private boolean allAccounts;

    private final MessageListHandler handler = new MessageListHandler(this);

    private SortType sortType = SortType.SORT_DATE;
    private boolean sortAscending = true;
    private boolean sortDateAscending = false;
    private int selectedCount = 0;
    private Set<Long> selected = new HashSet<>();
    private ActionMode actionMode;
    private Boolean hasConnectivity;
    /**
     * Relevant messages for the current context when we have to remember the chosen messages
     * between user interactions (e.g. selecting a folder for move operation).
     */
    private List<MessageReference> activeMessages;
    private final ActionModeCallback actionModeCallback = new ActionModeCallback();
    MessageListFragmentListener fragmentListener;
    private boolean showingThreadedList;
    private boolean isThreadDisplay;
    private Context context;
    private final MessageListActivityListener activityListener = new MessageListActivityListener();
    private Preferences preferences;
    private MessageReference activeMessage;
    //默认第一页为1
    private int limitCount = 1;
    /**
     * {@code true} after {@link #onCreate(Bundle)} was executed. Used in {@link #updateTitle()} to
     * make sure we don't access member variables before initialization is complete.
     */
    private boolean initialized = false;
    private LocalBroadcastManager localBroadcastManager;
    private BroadcastReceiver cacheBroadcastReceiver;
    private IntentFilter cacheIntentFilter;

    /**
     * Stores the unique ID of the message the context menu was opened for.
     * <p>
     * We have to save this because the message list might change between the time the menu was
     * opened and when the user clicks on a menu item. When this happens the 'adapter position' that
     * is accessible via the {@code ContextMenu} object might correspond to another list item and we
     * would end up using/modifying the wrong message.
     * <p>
     * The value of this field is {@code 0} when no context menu is currently open.
     */
    private long contextMenuUniqueId = 0;


    private MessageListViewModel getViewModel() {
        return diContainer.getViewModel();
    }

    void folderLoading(String folder, boolean loading) {
        if (currentFolder != null && currentFolder.serverId.equals(folder)) {
            currentFolder.loading = loading;
        }
        updateFooterView();
    }

    public void updateTitle() {
        if (!initialized) {
            return;
        }

        setWindowTitle();
        if (!search.isManualSearch()) {
            setWindowProgress();
        }
    }

    private void setWindowProgress() {
        int level = 0;

        if (currentFolder != null && currentFolder.loading) {
            int folderTotal = activityListener.getFolderTotal();
            if (folderTotal > 0) {
                level = (MAX_PROGRESS * activityListener.getFolderCompleted()) / folderTotal;
                if (level > MAX_PROGRESS) {
                    level = MAX_PROGRESS;
                }
            }
        }

        fragmentListener.setMessageListProgress(level);
    }

    private void setWindowTitle() {
        // regular folder content display
        if (!isManualSearch() && singleFolderMode) {
            /**
             * 当前Folder 是收件箱
             * 收件箱是在这获取的
             */
            String displayName = currentFolder.displayName;
            if (displayName.equals("Deleted Messages")) {
                displayName = "已删除";
            } else if (displayName.equals("Sent Messages")) {
                displayName = "已发送";
            } else if (displayName.equals("Drafts")) {
                displayName = "草稿箱";
            } else if (displayName.equals("Junk")) {
                displayName = "垃圾箱";
            }
            fragmentListener.setMessageListTitle(displayName);
        } else {
            // query result display.  This may be for a search folder as opposed to a user-initiated search.
            if (title != null) {
                // This was a search folder; the search folder has overridden our title.
                fragmentListener.setMessageListTitle(title);
            } else {
                // This is a search result; set it to the default search result line.
                fragmentListener.setMessageListTitle(getString(R.string.search_results));
            }
        }
    }

    void progress(final boolean progress) {
        if (swipeRefreshLayout != null && !progress) {
            swipeRefreshLayout.setRefreshing(false);
        }
        fragmentListener.setMessageListProgressEnabled(progress);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

        if (view == footerView) {
            if (currentFolder != null && !search.isManualSearch() && currentFolder.moreMessages) {
                /**
                 * 分页加载
                 */
                //TODO  加载更多
                messagingController.loadMoreMessages(account, folderServerId, null);

            } else if (currentFolder != null && isRemoteSearch() &&
                    extraSearchResults != null && extraSearchResults.size() > 0) {

                int numResults = extraSearchResults.size();
                int limit = account.getRemoteSearchNumResults();

                List<String> toProcess = extraSearchResults;

                if (limit > 0 && numResults > limit) {
                    toProcess = toProcess.subList(0, limit);
                    extraSearchResults = extraSearchResults.subList(limit,
                            extraSearchResults.size());
                } else {
                    extraSearchResults = null;
                    updateFooter(null);
                }

                messagingController.loadSearchResults(account, currentFolder.serverId, toProcess, activityListener);
            }

            return;
        }

        int adapterPosition = listViewToAdapterPosition(position);
        MessageListItem messageListItem = adapter.getItem(adapterPosition);
        //在这里加
        fragmentListener.setActionbar(true);
        if (selectedCount > 0) {
            toggleMessageSelect(position);
        } else {
            if (showingThreadedList && messageListItem.getThreadCount() > 1) {
                Account account = messageListItem.getAccount();
                String folderServerId = messageListItem.getFolderServerId();
//                SharedPreferences sp = context.getSharedPreferences("ThreadCount", Context.MODE_PRIVATE);
//                int threadCount = messageListItem.getThreadCount();
//                sp.edit().putBoolean("key", true).putInt("count", threadCount).commit();


                // If threading is enabled and this item represents a thread, display the thread contents.
                long rootId = messageListItem.getThreadRoot();
                fragmentListener.showThread(account, folderServerId, rootId);

            } else {
                // This item represents a message; just display the message.
                isClickItem = true;
                openMessageAtPosition(adapterPosition);
            }
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        context = activity.getApplicationContext();

        try {
            fragmentListener = (MessageListFragmentListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.getClass() +
                    " must implement MessageListFragmentListener");
        }
    }

    //TODO onCreate
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        folderNameFormatter = folderNameFormatterFactory.create(requireActivity());
        Context appContext = getActivity().getApplicationContext();
        sp_list = appContext.getSharedPreferences("aa", Context.MODE_PRIVATE);
        preferences = Preferences.getPreferences(appContext);
        messagingController = MessagingController.getInstance(getActivity().getApplication());

        restoreInstanceState(savedInstanceState);
        decodeArguments();

        createCacheBroadcastReceiver(appContext);
        /**
         * 这是加载到的数据 传到adapter
         */
        //TODO 这是加载到的数据 传到adapter
        getViewModel().getMessageListLiveData().observe(this, this::setMessageList);
        initialized = true;
    }


//    public void setMessageList1(MessageListInfo messageListInfo) {
//        ll_bar.setVisibility(View.VISIBLE);
//        tv_loading.setVisibility(View.GONE);
//        SharedPreferences dblistsp = context.getSharedPreferences("dblist", Context.MODE_PRIVATE);
//        //这是保存的数据库中条目的直
//        int key1 = dblistsp.getInt("key", 0);
//        //这是判断能否被整除 1可以 2不能整除
//        int anInt = dblistsp.getInt("int", 0);
//        List<MessageListItem> messageListItems = messageListInfo.getMessageListItems();
//        MyApplication.getInstance().getMessageitemdb().delete(FujianBeanDB.BIAO_NAME_MESSAGE, null, null);
//        String json = new Gson().toJson(messageListItems);
//        ContentValues values = new ContentValues();
//        values.put(FujianBeanDB.MESSAGELISTTOSTRING, json);
//        MyApplication.getInstance().getMessageitemdb().insert(FujianBeanDB.BIAO_NAME_MESSAGE, null, values);
//        values.clear();
//        Cursor cursor = MyApplication.getInstance().getMessageitemdb().query(FujianBeanDB.BIAO_NAME_MESSAGE, null, null, null, null, null, null, null);
//        while (cursor.moveToNext()) {
//            messageJson = cursor.getString(1);
//            Log.i("TAG", "SSSSSSSS.." + messageJson);
//        }
//        cursor.close();
    //TODO MessageListItem 中的    val displayName: CharSequence 改为 val displayName: String,

//        oList = new Gson().fromJson(messageJson, new TypeToken<List<MessageListItem>>() {
//        }.getType());
//        Log.i("TAG", "bbbbbbbbboSize.." + oList.size());
//        if (isThreadDisplay && messageListItems.isEmpty()) {
//            handler.goBack();
//            return;
//        }
//
//        swipeRefreshLayout.setRefreshing(false);
//        swipeRefreshLayout.setEnabled(isPullToRefreshAllowed());
//
//        if (isThreadDisplay) {
//            if (!messageListItems.isEmpty()) {
//                MessageListItem messageListItem = messageListItems.get(0);
//                title = messageListItem.getSubject();
//                if (!TextUtils.isEmpty(title)) {
//                    title = Utility.stripSubject(title);
//                }
//                if (TextUtils.isEmpty(title)) {
//                    title = getString(R.string.general_no_subject);
//                }
//                updateTitle();
//            } else {
//                //TODO: empty thread view -> return to full message list
//            }
//        }
//
//        cleanupSelected(messageListItems);
//        adapter.setSelected(selected);
//
//        updateContextMenu(messageListItems);
//        SharedPreferences sp = context.getSharedPreferences("ThreadCount", Context.MODE_PRIVATE);
//        boolean key = sp.getBoolean("key", false);
//        if (title.equals("搜索结果")) {
//            ll_bar.setVisibility(View.GONE);
//            this.adapter.setMessages(messageListItems);
//        } else if (key == true) {
//            ll_bar.setVisibility(View.GONE);
//            this.adapter.setMessages(messageListItems);
//            sp.edit().putBoolean("key", false).commit();
//        } else if (limitCount == 1) {
//            List<MessageListItem> mList = new ArrayList<>();
//            if (oList.size() > 0) {
//                if (oList.size() > 8) {
//                    for (int i = 0; i < 8; i++) {
//                        MessageListItem messageListItem = oList.get(i);
//                        mList.add(messageListItem);
//                    }
//                    this.adapter.setMessages(mList);
//                } else {
//                    this.adapter.setMessages(messageListItems);
//                }
//            }
//            SharedPreferences limit = getActivity().getSharedPreferences("save_limit", Context.MODE_PRIVATE);
//            limit.edit().putInt("key", limitCount).commit();
//        } else if (limitCount > 1) {
//            try {
//                //说明加载到了更多的值
//                if (key1 != 0 && key1 < oList.size()) {
//                    List<MessageListItem> mList = new ArrayList<>();
//                    for (int i = (limitCount - 1) * 8; i < 8 * limitCount; i++) {
//                        MessageListItem messageListItem = oList.get(i);
//                        mList.add(messageListItem);
//                    }
//                    this.adapter.setMessages(mList);
//                    SharedPreferences limit = getActivity().getSharedPreferences("save_limit", Context.MODE_PRIVATE);
//                    limit.edit().putInt("key", limitCount).commit();
//                    limit_count.setText("第" + limitCount + "页");
//                } else {//说明邮箱没有新的邮件发来了  加载最后一页  页数要减1   判断是否能整除
//                    if (anInt == 1) {
//                        List<MessageListItem> mList = new ArrayList<>();
//                        for (int i = (limitCount - 2) * 8; i < 8 * (limitCount - 1); i++) {
//                            MessageListItem messageListItem = oList.get(i);
//                            mList.add(messageListItem);
//                        }
//                        this.adapter.setMessages(mList);
//                        SharedPreferences limit = getActivity().getSharedPreferences("save_limit", Context.MODE_PRIVATE);
//                        limit.edit().putInt("key", limitCount).commit();
//                        limit_count.setText("第" + (limitCount - 1) + "页");
//                    } else if (anInt == 2) {
//                        List<MessageListItem> mList = new ArrayList<>();
//                        for (int i = (limitCount - 2) * 8; i < oList.size(); i++) {
//                            MessageListItem messageListItem = oList.get(i);
//                            mList.add(messageListItem);
//                        }
//                        this.adapter.setMessages(mList);
//                        SharedPreferences limit = getActivity().getSharedPreferences("save_limit", Context.MODE_PRIVATE);
//                        limit.edit().putInt("key", limitCount).commit();
//                        limit_count.setText("第" + (limitCount - 1) + "页");
//                    } else if (anInt == 3) {
//                        if (isClickItem) {
//                        } else {
//                            List<MessageListItem> mList = new ArrayList<>();
//                            for (int i = (limitCount - 1) * 8; i < oList.size(); i++) {
//                                MessageListItem messageListItem = oList.get(i);
//                                mList.add(messageListItem);
//                            }
//                            this.adapter.setMessages(mList);
//                            SharedPreferences limit = getActivity().getSharedPreferences("save_limit", Context.MODE_PRIVATE);
//                            limit.edit().putInt("key", limitCount).commit();
//                            right_message_list.setVisibility(View.GONE);
//                            limit_count.setText("没有更多数据了");
//                            isClickItem = false;
//                        }
//
//                    }
//                }
//            } catch (Exception e) {
//
//            }
////            try {
////                if (oList.size() >(limitCount - 1) * 8) {
////                    List<MessageListItem> mList = new ArrayList<>();
////                    for (int i = (limitCount - 1) * 8; i < 8 * limitCount; i++) {
////                        MessageListItem messageListItem = oList.get(i);
////                        mList.add(messageListItem);
////                    }
////                    this.adapter.setMessages(mList);
////                }
////                Log.i("tag", "bbbbbbbbb11111111");
////            } catch (Exception e) {
////                e.getMessage();
////                Log.i("tag", "bbbbbbbbb222222222");
////            }
//
//        }
//        resetActionMode();
//        computeBatchDirection();
//
//        messageListLoaded = true;
//
//        if (savedListState != null) {
//            handler.restoreListPosition();
//        }
//
//        fragmentListener.updateMenu();
//
//        if (folderServerId != null) {
//            currentFolder.moreMessages = messageListInfo.getHasMoreMessages();
////            updateFooterView();
//        }
//    }

    //todo onCreateView
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        layoutInflater = inflater;

        View view = inflater.inflate(R.layout.message_list_fragment, container, false);
        //TODO 绑定控件
        initializePullToRefresh(view);

        initializeLayout();
        //TODO 给RecyclerView传数据

//        listView.setVerticalFadingEdgeEnabled(false);

        return view;
    }

    @Override
    public void onDestroyView() {
        savedListState = listView.onSaveInstanceState();
        SharedPreferences sp = context.getSharedPreferences("state", Context.MODE_PRIVATE);
        Log.i("tag", "vvvvvvv..v");
        if (currentFolder != null) {
            String displayName = currentFolder.displayName;
            Log.i("tag", "vvvvvvv.." + displayName);
            sp.edit().putInt("count", limitCount).putString("folder", currentFolder.displayName).commit();
        }
        MyApplication.getInstance().getMessageitemdb().delete(FujianBeanDB.BIAO_NAME_MESSAGE, null, null);
        super.onDestroyView();
    }

    //TODO  onActivityCreated
    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        Log.i("tag", "vvvvvvvvvvvvvv3333333");
        initializeMessageList();

        // This needs to be done before loading the message list below
        initializeSortSettings();
        /**
         * 加载数据
         */
        //TODO  加载数据
        loadMessageList();

    }


    //TODO   保存数据
    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        saveSelectedMessages(outState);
        saveListState(outState);

        outState.putBoolean(STATE_REMOTE_SEARCH_PERFORMED, remoteSearchPerformed);
        if (activeMessage != null) {
            outState.putString(STATE_ACTIVE_MESSAGE, activeMessage.toIdentityString());
        }
    }

    /**
     * Restore the state of a previous {@link MessageListFragment} instance.
     *
     * @see #onSaveInstanceState(Bundle)
     */
    //TODO   恢复数据
    private void restoreInstanceState(Bundle savedInstanceState) {
        SharedPreferences sp = context.getSharedPreferences("state", Context.MODE_PRIVATE);
        SharedPreferences splogin = context.getSharedPreferences("login", Context.MODE_PRIVATE);
        Log.i("tag", "vvvvvvv..v1");
        int logNum = splogin.getInt("key", 0);
        if (logNum == 1) {//等于1说明  刚刚登陆的
            limitCount = 1;
            splogin.edit().putInt("key", 0).commit();
        } else {
            String displayName = sp.getString("folder", "收件箱");
            if (displayName.equals("收件箱")) {
                limitCount = sp.getInt("count", 1);
            } else {
                limitCount = 1;
            }
        }
        Log.i("tag", "nnnnnn" + limitCount + "logNum=" + logNum);
        if (savedInstanceState == null) {
            Log.i("tag", "nnnnnn");
            return;
        }
        restoreSelectedMessages(savedInstanceState);
        remoteSearchPerformed = savedInstanceState.getBoolean(STATE_REMOTE_SEARCH_PERFORMED);
        savedListState = savedInstanceState.getParcelable(STATE_MESSAGE_LIST);
        String messageReferenceString = savedInstanceState.getString(STATE_ACTIVE_MESSAGE);
        activeMessage = MessageReference.parse(messageReferenceString);
        if (adapter != null) adapter.setActiveMessage(activeMessage);
    }

    /**
     * Write the unique IDs of selected messages to a {@link Bundle}.
     */
    private void saveSelectedMessages(Bundle outState) {
        long[] selected = new long[this.selected.size()];
        int i = 0;
        for (Long id : this.selected) {
            selected[i++] = id;
        }
        outState.putLongArray(STATE_SELECTED_MESSAGES, selected);
    }

    /**
     * Restore selected messages from a {@link Bundle}.
     */
    private void restoreSelectedMessages(Bundle savedInstanceState) {
        long[] selected = savedInstanceState.getLongArray(STATE_SELECTED_MESSAGES);
        if (selected != null) {
            for (long id : selected) {
                this.selected.add(id);
            }
        }
    }

    //TODO 保存List状态
    private void saveListState(Bundle outState) {
        if (savedListState != null) {
            // The previously saved state was never restored, so just use that.
            outState.putParcelable(STATE_MESSAGE_LIST, savedListState);
        } else if (listView != null) {
            outState.putParcelable(STATE_MESSAGE_LIST, listView.onSaveInstanceState());
        }
    }

    //TODO 排序设置
    private void initializeSortSettings() {
        if (singleAccountMode) {
            sortType = account.getSortType();
            sortAscending = account.isSortAscending(sortType);
            sortDateAscending = account.isSortAscending(SortType.SORT_DATE);
        } else {
            sortType = K9.getSortType();
            sortAscending = K9.isSortAscending(sortType);
            sortDateAscending = K9.isSortAscending(SortType.SORT_DATE);
        }
    }

    private void decodeArguments() {
        Bundle args = getArguments();
        showingThreadedList = args.getBoolean(ARG_THREADED_LIST, false);
        isThreadDisplay = args.getBoolean(ARG_IS_THREAD_DISPLAY, false);
        search = args.getParcelable(ARG_SEARCH);
        title = search.getName();

        List<Account> searchAccounts = getAccountsFromLocalSearch(search, preferences);
        allAccounts = search.searchAllAccounts();
        if (searchAccounts.size() == 1) {
            Account singleAccount = searchAccounts.get(0);
            singleAccountMode = true;
            account = singleAccount;
            accountUuids = new String[]{singleAccount.getUuid()};
        } else {
            String[] searchAccountUuids = new String[searchAccounts.size()];
            for (int i = 0, len = searchAccounts.size(); i < len; i++) {
                searchAccountUuids[i] = searchAccounts.get(i).getUuid();
            }

            singleAccountMode = false;
            account = null;
            accountUuids = searchAccountUuids;
        }

        singleFolderMode = false;
        if (singleAccountMode && (search.getFolderServerIds().size() == 1)) {
            singleFolderMode = true;
            folderServerId = search.getFolderServerIds().get(0);
            currentFolder = getFolderInfoHolder(folderServerId, account);
        }
    }

    //TODO listView 适配
    private void initializeMessageList() {
        adapter = new MessageListAdapter(
                requireContext(),
                requireActivity().getTheme(),
                getResources(),
                layoutInflater,
                ContactPicture.getContactPictureLoader(),
                this,
                getMessageListAppearance()
        );

        if (folderServerId != null) {
            currentFolder = getFolderInfoHolder(folderServerId, account);
            Log.i("tag", "什么鬼。。" + currentFolder.displayName);
        }

        if (singleFolderMode) {
            //还原
//            listView.addFooterView(getFooterView(listView));
//            updateFooterView();
        }

        listView.setAdapter(adapter);
    }

    private MessageListAppearance getMessageListAppearance() {
        boolean showAccountChip = !isSingleAccountMode();
        return new MessageListAppearance(
                K9.getFontSizes(),
                K9.getMessageListPreviewLines(),
                K9.isShowMessageListStars(),
                K9.isMessageListSenderAboveSubject(),
                K9.isShowContactPicture(),
                showingThreadedList,
                K9.isUseBackgroundAsUnreadIndicator(),
                showAccountChip
        );
    }

    private void createCacheBroadcastReceiver(Context appContext) {
        localBroadcastManager = LocalBroadcastManager.getInstance(appContext);

        cacheBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                adapter.notifyDataSetChanged();
            }
        };

        cacheIntentFilter = new IntentFilter(EmailProviderCache.ACTION_CACHE_UPDATED);
    }

    private FolderInfoHolder getFolderInfoHolder(String folderServerId, Account account) {
        try {
            LocalFolder localFolder = MlfUtils.getOpenFolder(folderServerId, account);
            return new FolderInfoHolder(folderNameFormatter, localFolder, account);
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        localBroadcastManager.unregisterReceiver(cacheBroadcastReceiver);
        messagingController.removeListener(activityListener);
    }

    /**
     * On resume we refresh messages for the folder that is currently open.
     * This guarantees that things like unread message count and read status
     * are updated.
     */
    @Override
    public void onResume() {
        super.onResume();

        // Check if we have connectivity.  Cache the value.
        if (hasConnectivity == null) {
            hasConnectivity = Utility.hasConnectivity(getActivity().getApplication());
        }

        localBroadcastManager.registerReceiver(cacheBroadcastReceiver, cacheIntentFilter);
        messagingController.addListener(activityListener);

        //Cancel pending new mail notifications when we open an account
        List<Account> accountsWithNotification;

        Account account = this.account;
        if (account != null) {
            accountsWithNotification = Collections.singletonList(account);
        } else {
            accountsWithNotification = preferences.getAccounts();
        }

        for (Account accountWithNotification : accountsWithNotification) {
            messagingController.cancelNotificationsForAccount(accountWithNotification);
        }

        if (this.account != null && folderServerId != null && !search.isManualSearch()) {
            messagingController.getFolderUnreadMessageCount(this.account, folderServerId, activityListener);
        }

        updateTitle();
    }

    private void initializePullToRefresh(View layout) {
        ll_bar = layout.findViewById(R.id.LL);
        ll_bar.setVisibility(View.VISIBLE);
        tv_loading = layout.findViewById(R.id.tv_loading);
        left_message_list = layout.findViewById(R.id.left_message);
        right_message_list = layout.findViewById(R.id.right_message);
        limit_count = layout.findViewById(R.id.limit_count);
//        limit_count.setText("第" + limitCount + "页");
        swipeRefreshLayout = layout.findViewById(R.id.swiperefresh);
        listView = layout.findViewById(R.id.message_list);
        Log.i("tag", "vvvvvvvvvvvvvv444444");
        if (isRemoteSearchAllowed()) {
            swipeRefreshLayout.setOnRefreshListener(
                    () -> onRemoteSearchRequested()
            );
        } else if (isCheckMailSupported()) {
            swipeRefreshLayout.setOnRefreshListener(
                    () -> checkMail()
            );
        }

        // Disable pull-to-refresh until the message list has been loaded
        swipeRefreshLayout.setEnabled(false);
        left_message_list.setOnClickListener(this);
        right_message_list.setOnClickListener(this);
    }

    private void initializeLayout() {
//        listView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
//        listView.setLongClickable(true);
//        listView.setFastScrollEnabled(true);
//        listView.setScrollingCacheEnabled(false);
        listView.setOnItemClickListener(this);

        registerForContextMenu(listView);
//        registerForContextMenu(mRecyclerView);
    }

    public void onCompose() {
        if (!singleAccountMode) {
            /*
             * If we have a query string, we don't have an account to let
             * compose start the default action.
             */
            fragmentListener.onCompose(null);
        } else {
            fragmentListener.onCompose(account);
        }
    }

    private void onReply(MessageReference messageReference) {
        fragmentListener.onReply(messageReference);
    }

    private void onReplyAll(MessageReference messageReference) {
        fragmentListener.onReplyAll(messageReference);
    }

    private void onForward(MessageReference messageReference) {
        fragmentListener.onForward(messageReference);
    }

    public void onForwardAsAttachment(MessageReference messageReference) {
        fragmentListener.onForwardAsAttachment(messageReference);
    }

    private void onResendMessage(MessageReference messageReference) {
        fragmentListener.onResendMessage(messageReference);
    }

    public void changeSort(SortType sortType) {
        Boolean sortAscending = (this.sortType == sortType) ? !this.sortAscending : null;
        changeSort(sortType, sortAscending);
    }

    /**
     * User has requested a remote search.  Setup the bundle and start the intent.
     */
    private void onRemoteSearchRequested() {
        String searchAccount;
        String searchFolder;

        searchAccount = account.getUuid();
        searchFolder = currentFolder.serverId;

        String queryString = search.getRemoteSearchArguments();

        remoteSearchPerformed = true;
        remoteSearchFuture = messagingController.searchRemoteMessages(searchAccount, searchFolder,
                queryString, null, null, activityListener);

        swipeRefreshLayout.setEnabled(false);

        fragmentListener.remoteSearchStarted();
    }

    /**
     * Change the sort type and sort order used for the message list.
     *
     * @param sortType      Specifies which field to use for sorting the message list.
     * @param sortAscending Specifies the sort order. If this argument is {@code null} the default search order
     *                      for the sort type is used.
     */
    // FIXME: Don't save the changes in the UI thread
    private void changeSort(SortType sortType, Boolean sortAscending) {
        this.sortType = sortType;

        Account account = this.account;

        if (account != null) {
            account.setSortType(this.sortType);

            if (sortAscending == null) {
                this.sortAscending = account.isSortAscending(this.sortType);
            } else {
                this.sortAscending = sortAscending;
            }
            account.setSortAscending(this.sortType, this.sortAscending);
            sortDateAscending = account.isSortAscending(SortType.SORT_DATE);

            Preferences.getPreferences(getContext()).saveAccount(account);
        } else {
            K9.setSortType(this.sortType);

            if (sortAscending == null) {
                this.sortAscending = K9.isSortAscending(this.sortType);
            } else {
                this.sortAscending = sortAscending;
            }
            K9.setSortAscending(this.sortType, this.sortAscending);
            sortDateAscending = K9.isSortAscending(SortType.SORT_DATE);

            StorageEditor editor = preferences.createStorageEditor();
            K9.save(editor);
            editor.commit();
        }

        reSort();
    }

    private void reSort() {
        int toastString = sortTypeToastProvider.getToast(sortType, sortAscending);

        Toast toast = Toast.makeText(getActivity(), toastString, Toast.LENGTH_SHORT);
        toast.show();

        loadMessageList();
    }

    public void onCycleSort() {
        SortType[] sorts = SortType.values();
        int curIndex = 0;

        for (int i = 0; i < sorts.length; i++) {
            if (sorts[i] == sortType) {
                curIndex = i;
                break;
            }
        }

        curIndex++;

        if (curIndex == sorts.length) {
            curIndex = 0;
        }

        changeSort(sorts[curIndex]);
    }

    private void onDelete(MessageReference message) {
        onDelete(Collections.singletonList(message));
    }

    private void onDelete(List<MessageReference> messages) {
        if (K9.isConfirmDelete()) {
            // remember the message selection for #onCreateDialog(int)
            activeMessages = messages;
            showDialog(R.id.dialog_confirm_delete);
        } else {
            onDeleteConfirmed(messages);
        }
    }

    private void onDeleteConfirmed(List<MessageReference> messages) {
        if (showingThreadedList) {
            messagingController.deleteThreads(messages);
        } else {
            messagingController.deleteMessages(messages, null);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            return;
        }

        switch (requestCode) {
            case ACTIVITY_CHOOSE_FOLDER_MOVE:
            case ACTIVITY_CHOOSE_FOLDER_COPY: {
                if (data == null) {
                    return;
                }

                final String destFolder = data.getStringExtra(ChooseFolderActivity.RESULT_SELECTED_FOLDER);
                final List<MessageReference> messages = activeMessages;

                if (destFolder != null) {

                    activeMessages = null; // don't need it any more

                    if (messages.size() > 0) {
                        MlfUtils.setLastSelectedFolder(preferences, messages, destFolder);
                    }

                    switch (requestCode) {
                        case ACTIVITY_CHOOSE_FOLDER_MOVE:
                            move(messages, destFolder);
                            break;

                        case ACTIVITY_CHOOSE_FOLDER_COPY:
                            copy(messages, destFolder);
                            break;
                    }
                }
                break;
            }
        }
    }

    public void onExpunge() {
        if (currentFolder != null) {
            onExpunge(account, currentFolder.serverId);
        }
    }

    private void onExpunge(final Account account, String folderServerId) {
        messagingController.expunge(account, folderServerId);
    }

    public void onEmptyTrash() {
        if (isShowingTrashFolder()) {
            showDialog(R.id.dialog_confirm_empty_trash);
        }
    }

    public boolean isShowingTrashFolder() {
        return singleFolderMode && currentFolder != null && currentFolder.serverId.equals(account.getTrashFolder());
    }

    private void showDialog(int dialogId) {
        DialogFragment fragment;
        if (dialogId == R.id.dialog_confirm_spam) {
            String title = getString(R.string.dialog_confirm_spam_title);

            int selectionSize = activeMessages.size();
            String message = getResources().getQuantityString(
                    R.plurals.dialog_confirm_spam_message, selectionSize, selectionSize);

            String confirmText = getString(R.string.dialog_confirm_spam_confirm_button);
            String cancelText = getString(R.string.dialog_confirm_spam_cancel_button);

            fragment = ConfirmationDialogFragment.newInstance(dialogId, title, message,
                    confirmText, cancelText, null);
        } else if (dialogId == R.id.dialog_confirm_delete) {
            String title = getString(R.string.dialog_confirm_delete_title);

            int selectionSize = activeMessages.size();
            String message = getResources().getQuantityString(
                    R.plurals.dialog_confirm_delete_messages, selectionSize,
                    selectionSize);

            String confirmText = getString(R.string.dialog_confirm_delete_confirm_button);
            String cancelText = getString(R.string.dialog_confirm_delete_cancel_button);

            fragment = ConfirmationDialogFragment.newInstance(dialogId, title, message,
                    confirmText, cancelText, null);
        } else if (dialogId == R.id.dialog_confirm_mark_all_as_read) {
            String title = getString(R.string.dialog_confirm_mark_all_as_read_title);
            String message = getString(R.string.dialog_confirm_mark_all_as_read_message);

            String confirmText = getString(R.string.dialog_confirm_mark_all_as_read_confirm_button);
            String cancelText = getString(R.string.dialog_confirm_mark_all_as_read_cancel_button);

            fragment = ConfirmationDialogFragment.newInstance(dialogId, title, message, confirmText, cancelText, null);
        } else if (dialogId == R.id.dialog_confirm_empty_trash) {
            String title = getString(R.string.dialog_confirm_empty_trash_title);
            String message = getString(R.string.dialog_confirm_empty_trash_message);

            String confirmText = getString(R.string.dialog_confirm_delete_confirm_button);
            String cancelText = getString(R.string.dialog_confirm_delete_cancel_button);

            fragment = ConfirmationDialogFragment.newInstance(dialogId, title, message,
                    confirmText, cancelText, null);
        } else {
            throw new RuntimeException("Called showDialog(int) with unknown dialog id.");
        }

        fragment.setTargetFragment(this, dialogId);
        fragment.show(getParentFragmentManager(), getDialogTag(dialogId));
    }

    private String getDialogTag(int dialogId) {
        return "dialog-" + dialogId;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.set_sort_date) {
            changeSort(SortType.SORT_DATE);
            return true;
        } else if (id == R.id.set_sort_arrival) {
            changeSort(SortType.SORT_ARRIVAL);
            return true;
        } else if (id == R.id.set_sort_subject) {
            changeSort(SortType.SORT_SUBJECT);
            return true;
        } else if (id == R.id.set_sort_sender) {
            changeSort(SortType.SORT_SENDER);
            return true;
        } else if (id == R.id.set_sort_flag) {
            changeSort(SortType.SORT_FLAGGED);
            return true;
        } else if (id == R.id.set_sort_unread) {
            changeSort(SortType.SORT_UNREAD);
            return true;
        } else if (id == R.id.set_sort_attach) {
            changeSort(SortType.SORT_ATTACHMENT);
            return true;
        } else if (id == R.id.select_all) {
            selectAll();
            return true;
        }

        if (!singleAccountMode) {
            // None of the options after this point are "safe" for search results
            //TODO: This is not true for "unread" and "starred" searches in regular folders
            return false;
        }

        if (id == R.id.send_messages) {
            onSendPendingMessages();
            return true;
        } else if (id == R.id.expunge) {
            if (currentFolder != null) {
                onExpunge(account, currentFolder.serverId);
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    public void onSendPendingMessages() {
        messagingController.sendPendingMessages(account, null);
    }

    @Override
    public boolean onContextItemSelected(android.view.MenuItem item) {
        if (contextMenuUniqueId == 0) {
            return false;
        }

        int adapterPosition = getPositionForUniqueId(contextMenuUniqueId);
        if (adapterPosition == AdapterView.INVALID_POSITION) {
            return false;
        }

        int id = item.getItemId();
        if (id == R.id.deselect || id == R.id.select) {
            toggleMessageSelectWithAdapterPosition(adapterPosition);
        } else if (id == R.id.reply) {
            onReply(getMessageAtPosition(adapterPosition));
        } else if (id == R.id.reply_all) {
            onReplyAll(getMessageAtPosition(adapterPosition));
        } else if (id == R.id.forward) {
            onForward(getMessageAtPosition(adapterPosition));
        } else if (id == R.id.forward_as_attachment) {
            onForwardAsAttachment(getMessageAtPosition(adapterPosition));
        } else if (id == R.id.send_again) {
            onResendMessage(getMessageAtPosition(adapterPosition));
            selectedCount = 0;
        } else if (id == R.id.same_sender) {
            MessageListItem messageListItem = adapter.getItem(adapterPosition);
            String senderAddress = messageListItem.getSenderAddress();
            if (senderAddress != null) {
                fragmentListener.showMoreFromSameSender(senderAddress);
            }
        } else if (id == R.id.delete) {
            MessageReference message = getMessageAtPosition(adapterPosition);
            onDelete(message);
        } else if (id == R.id.mark_as_read) {
            setFlag(adapterPosition, Flag.SEEN, true);
        } else if (id == R.id.mark_as_unread) {
            setFlag(adapterPosition, Flag.SEEN, false);
        } else if (id == R.id.flag) {
            setFlag(adapterPosition, Flag.FLAGGED, true);
        } else if (id == R.id.unflag) {
            setFlag(adapterPosition, Flag.FLAGGED, false);
        } else if (id == R.id.archive) {        // only if the account supports this
            onArchive(getMessageAtPosition(adapterPosition));
        } else if (id == R.id.spam) {
            onSpam(getMessageAtPosition(adapterPosition));
        } else if (id == R.id.move) {
            onMove(getMessageAtPosition(adapterPosition));
        } else if (id == R.id.copy) {
            onCopy(getMessageAtPosition(adapterPosition));
        } else if (id == R.id.debug_delete_locally) {       // debug options
            onDebugClearLocally(getMessageAtPosition(adapterPosition));
        }

        contextMenuUniqueId = 0;
        return true;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        int adapterPosition = listViewToAdapterPosition(info.position);
        MessageListItem messageListItem = adapter.getItem(adapterPosition);

        getActivity().getMenuInflater().inflate(R.menu.message_list_item_context, menu);
        menu.findItem(R.id.debug_delete_locally).setVisible(K9.DEVELOPER_MODE);

        contextMenuUniqueId = messageListItem.getUniqueId();
        Account account = messageListItem.getAccount();

        String subject = messageListItem.getSubject();
        boolean read = messageListItem.isRead();
        boolean flagged = messageListItem.isStarred();

        menu.setHeaderTitle(subject);

        if (selected.contains(contextMenuUniqueId)) {
            menu.findItem(R.id.select).setVisible(false);
        } else {
            menu.findItem(R.id.deselect).setVisible(false);
        }

        if (read) {
            menu.findItem(R.id.mark_as_read).setVisible(false);
        } else {
            menu.findItem(R.id.mark_as_unread).setVisible(false);
        }

        if (flagged) {
            menu.findItem(R.id.flag).setVisible(false);
        } else {
            menu.findItem(R.id.unflag).setVisible(false);
        }

        if (!messagingController.isCopyCapable(account)) {
            menu.findItem(R.id.copy).setVisible(false);
        }

        if (!messagingController.isMoveCapable(account)) {
            menu.findItem(R.id.move).setVisible(false);
            menu.findItem(R.id.archive).setVisible(false);
            menu.findItem(R.id.spam).setVisible(false);
        }

        if (!account.hasArchiveFolder()) {
            menu.findItem(R.id.archive).setVisible(false);
        }

        if (!account.hasSpamFolder()) {
            menu.findItem(R.id.spam).setVisible(false);
        }

    }


    private int listViewToAdapterPosition(int position) {
        if (position >= 0 && position < adapter.getCount()) {
            return position;
        }

        return AdapterView.INVALID_POSITION;
    }

    private int adapterToListViewPosition(int position) {
        if (position >= 0 && position < adapter.getCount()) {
            return position;
        }

        return AdapterView.INVALID_POSITION;
    }

    //TODO 点击事件
    //TODO 点击事件
    @Override
    public void onClick(View v) {
        if (NetworkUtils.isNetWorkAvailable(getActivity())) {
            int id = v.getId();
            if (id == R.id.left_message) {//往前翻页

                if (limitCount > 1) {
                    if (messageListItems.size()>0){
                        limitCount--;
                        List<MessageListItem> mList = new ArrayList<>();
                        for (int i = (limitCount - 1) * 8; i < 8 * limitCount; i++) {
                            MessageListItem messageListItem = messageListItems.get(i);
                            mList.add(messageListItem);
                        }
                        this.adapter.setMessages(mList);
                        right_message_list.setVisibility(View.VISIBLE);
                        limit_count.setText("第" + limitCount + "页");
                        Save_Limit(limitCount);
                    }else{
                        Toast.makeText(context,"没有数据",Toast.LENGTH_SHORT).show();
                    }
                }
            } else if (id == R.id.right_message) { //向后翻页
                String displayName = currentFolder.displayName;
                if (displayName.equals("收件箱")) {
                    limitCount++;
                    int messageListSize = messageListItems.size();
                    //如果没有更多数据了直接拿到Messagelist的所有数据做分页
                    if (!currentFolder.moreMessages) {
                        //能整除
                        if (messageListSize % 8 == 0) {
                            int limit_num1 = messageListSize / 8;
                            if (limitCount == limit_num1) { //如果相等说明到了最后一页
                                List<MessageListItem> mList = new ArrayList<>();
                                for (int i = (limitCount - 1) * 8; i < 8 * limitCount; i++) {
                                    MessageListItem messageListItem = messageListItems.get(i);
                                    mList.add(messageListItem);
                                }
                                this.adapter.setMessages(mList);
                                right_message_list.setVisibility(View.GONE);
                                limit_count.setText("没有更多数据了");
                                Save_Limit(limitCount);
                            } else {  //否则不相等说明 没有翻到最后一页呢
                                List<MessageListItem> mList = new ArrayList<>();
                                for (int i = (limitCount - 1) * 8; i < 8 * limitCount; i++) {
                                    MessageListItem messageListItem = messageListItems.get(i);
                                    mList.add(messageListItem);
                                }
                                this.adapter.setMessages(mList);
                                limit_count.setText("第" + limitCount + "页");
                                Save_Limit(limitCount);
                            }
                        } else {
                            int limit_num1 = messageListSize / 8 + 1;
                            if (limitCount == limit_num1) { //如果相等说明到了最后一页
                                List<MessageListItem> mList = new ArrayList<>();
                                for (int i = (limitCount - 1) * 8; i < messageListItems.size(); i++) {
                                    MessageListItem messageListItem = messageListItems.get(i);
                                    mList.add(messageListItem);
                                }
                                this.adapter.setMessages(mList);
                                right_message_list.setVisibility(View.GONE);
                                limit_count.setText("没有更多数据了");
                                Save_Limit(limitCount);
                            } else {
                                List<MessageListItem> mList = new ArrayList<>();
                                for (int i = (limitCount - 1) * 8; i < 8 * limitCount; i++) {
                                    MessageListItem messageListItem = messageListItems.get(i);
                                    mList.add(messageListItem);
                                }
                                this.adapter.setMessages(mList);
                                limit_count.setText("第" + limitCount + "页");
                                Save_Limit(limitCount);
                            }
                        }
                    } else { //如果还有更多数据  翻到倒数第二页 就直接加载数据
                        //能整除
                        if (messageListItems.size() / 8 == 0) {
                            limit_num = messageListItems.size() / 8;
                        } else {
                            limit_num = messageListItems.size() / 8 + 1;
                        }
                        Log.i("tag", "vvvvvvvvvvvvvvvv." + messageListItems.size());
                        //TODO  这具体提前几页进行加载是可控的    这待改动看端点
                        if (limitCount == (limit_num - 5)) { //就是翻到倒数第二页 提前加载数据
                            Log.i("tag", "vvvvvvvvvvvvvvvv." + limitCount + "11vvv.." + limit_num);
                            messagingController.loadMoreMessages(account, folderServerId, null);
                            List<MessageListItem> mList = new ArrayList<>();
                            for (int i = (limitCount - 1) * 8; i < 8 * limitCount; i++) {
                                MessageListItem messageListItem = messageListItems.get(i);
                                mList.add(messageListItem);
                            }
                            this.adapter.setMessages(mList);
                            limit_count.setText("第" + limitCount + "页");
                            Save_Limit(limitCount);
                        } else {//不是倒数第二页的情况
                            try {
                                List<MessageListItem> mList = new ArrayList<>();
                                for (int i = (limitCount - 1) * 8; i < 8 * limitCount; i++) {
                                    MessageListItem messageListItem = messageListItems.get(i);
                                    mList.add(messageListItem);
                                }
                                this.adapter.setMessages(mList);
                                limit_count.setText("第" + limitCount + "页");
                                Save_Limit(limitCount);

                            } catch (Exception e) {
                                limitCount--;
                                Log.i("tag", "vvvvvvvvvvvvvvvv.--" + limitCount);
                                Toast.makeText(getActivity(), "请等待加载进度结束", Toast.LENGTH_LONG).show();
                            }
                        }
                    }
                } else {
                    int messageListSize = messageListItems.size();
                    if (messageListSize>0){
                        limitCount++;
                        //能整除
                        if (messageListSize % 8 == 0) {
                            int limit_num1 = messageListSize / 8;
                            if (limitCount == limit_num1) { //如果相等说明到了最后一页
                                List<MessageListItem> mList = new ArrayList<>();
                                for (int i = (limitCount - 1) * 8; i < 8 * limitCount; i++) {
                                    MessageListItem messageListItem = messageListItems.get(i);
                                    mList.add(messageListItem);
                                }
                                this.adapter.setMessages(mList);
                                right_message_list.setVisibility(View.GONE);
                                limit_count.setText("没有更多数据了");
                                Save_Limit(limitCount);
                            } else {  //否则不相等说明 没有翻到最后一页呢
                                List<MessageListItem> mList = new ArrayList<>();
                                for (int i = (limitCount - 1) * 8; i < 8 * limitCount; i++) {
                                    MessageListItem messageListItem = messageListItems.get(i);
                                    mList.add(messageListItem);
                                }
                                this.adapter.setMessages(mList);
                                limit_count.setText("第" + limitCount + "页");
                                Save_Limit(limitCount);
                            }
                        } else {
                            int limit_num1 = messageListSize / 8 + 1;
                            if (limitCount == limit_num1) { //如果相等说明到了最后一页
                                List<MessageListItem> mList = new ArrayList<>();
                                for (int i = (limitCount - 1) * 8; i < messageListItems.size(); i++) {
                                    MessageListItem messageListItem = messageListItems.get(i);
                                    mList.add(messageListItem);
                                }
                                this.adapter.setMessages(mList);
                                right_message_list.setVisibility(View.GONE);
                                limit_count.setText("没有更多数据了");
                                Save_Limit(limitCount);
                            } else {
                                List<MessageListItem> mList = new ArrayList<>();
                                for (int i = (limitCount - 1) * 8; i < 8 * limitCount; i++) {
                                    MessageListItem messageListItem = messageListItems.get(i);
                                    mList.add(messageListItem);
                                }
                                this.adapter.setMessages(mList);
                                limit_count.setText("第" + limitCount + "页");
                                Save_Limit(limitCount);
                            }
                        }




                    }else{
                        Toast.makeText(context,"没有数据",Toast.LENGTH_SHORT).show();
                    }














                }
            }
        }
    }

    private void Save_Limit(int limitCount) {
        SharedPreferences limit = getActivity().getSharedPreferences("save_limit", Context.MODE_PRIVATE);
        limit.edit().putInt("key", limitCount).commit();
    }

    public void setMessageList(MessageListInfo messageListInfo) {
        if (currentFolder == null) {
            Log.i("tag", "xxxxxxxxxxxxx1");
        } else {
            Log.i("tag", "xxxxxxxxxxxxx1" + currentFolder.displayName);
        }
        left_message_list.setVisibility(View.VISIBLE);
        right_message_list.setVisibility(View.VISIBLE);
        messageListItems = messageListInfo.getMessageListItems();
        Log.i("tag", "bbbbbbbb" + messageListItems.size());
        if (isThreadDisplay && messageListItems.isEmpty()) {
            handler.goBack();
            return;
        }

        swipeRefreshLayout.setRefreshing(false);
        swipeRefreshLayout.setEnabled(isPullToRefreshAllowed());

        if (isThreadDisplay) {
            if (!messageListItems.isEmpty()) {
                MessageListItem messageListItem = messageListItems.get(0);
                title = messageListItem.getSubject();
                if (!TextUtils.isEmpty(title)) {
                    title = Utility.stripSubject(title);
                }
                if (TextUtils.isEmpty(title)) {
                    title = getString(R.string.general_no_subject);
                }
                updateTitle();
            } else {
                //TODO: empty thread view -> return to full message list
            }
        }

        cleanupSelected(messageListItems);
        adapter.setSelected(selected);

        updateContextMenu(messageListItems);


//        SharedPreferences sp = context.getSharedPreferences("ThreadCount", Context.MODE_PRIVATE);
//        boolean key = sp.getBoolean("key", false);
//        int count = sp.getInt("count", 0);
//        if (key == true && count == messageListItems.size()) {
        if (currentFolder == null) {
            Log.i("tag", "xxxxxxxxxxxxx1");
            ll_bar.setVisibility(View.GONE);
            this.adapter.setMessages(messageListItems);
            Save_Limit(1);
        } else if (title.equals("搜索结果")) {
            ll_bar.setVisibility(View.GONE);
            this.adapter.setMessages(messageListItems);
        } else if (limitCount == 1) {
            if (messageListItems.size() > 0) {
                if (messageListItems.size() > 8) {
                    List<MessageListItem> mList = new ArrayList<>();
                    for (int i = 0; i < 8; i++) {
                        MessageListItem messageListItem = messageListItems.get(i);
                        mList.add(messageListItem);
                    }
                    this.adapter.setMessages(mList);
                    limit_count.setText("第" + limitCount + "页");
                } else {
                    this.adapter.setMessages(messageListItems);
                    left_message_list.setVisibility(View.GONE);
                    right_message_list.setVisibility(View.GONE);
                    limit_count.setText("没有更多数据了");
                }
            }
            Save_Limit(limitCount);
        } else if (limitCount > 1) {
            Log.i("tag", "bbbbbbbb" + limitCount + ".................." + messageListItems.size());
            //待处理如果是最后1页的情况
            List<MessageListItem> mList = new ArrayList<>();
            int size = messageListItems.size() > 8 * limitCount ? 8 * limitCount : messageListItems.size();
            for (int i = (limitCount - 1) * 8; i < size; i++) {
                MessageListItem messageListItem = messageListItems.get(i);
                mList.add(messageListItem);
            }
            this.adapter.setMessages(mList);
            Save_Limit(limitCount);
            if (messageListItems.size() > 8 * limitCount) {
                limit_count.setText("第" + limitCount + "页");
            } else {
                right_message_list.setVisibility(View.GONE);
                limit_count.setText("没有更多数据了");
            }
        }
        resetActionMode();
        computeBatchDirection();

        messageListLoaded = true;

        if (savedListState != null) {
            handler.restoreListPosition();
        }

        fragmentListener.updateMenu();
        if (folderServerId != null) {
            currentFolder.moreMessages = messageListInfo.getHasMoreMessages();
            Log.i("tag", "currentFolder.moreMessages.." + currentFolder.moreMessages);
            updateFooterView();
        }
    }


//    @Override
//    public void onClick(View v) {
//        if (NetworkUtils.isNetWorkAvailable(getActivity())) {
//            int id = v.getId();
//            if (id == R.id.left_message) {
//                try {
//                    if (limitCount > 1) {
//                        limitCount--;
//                        List<MessageListItem> mList = new ArrayList<>();
//                        for (int i = (limitCount - 1) * 8; i < 8 * limitCount; i++) {
//                            MessageListItem messageListItem = oList.get(i);
//                            mList.add(messageListItem);
//                        }
//                        this.adapter.setMessages(mList);
//                        right_message_list.setVisibility(View.VISIBLE);
//                        SharedPreferences limit = getActivity().getSharedPreferences("save_limit", Context.MODE_PRIVATE);
//                        limit.edit().putInt("key", limitCount).commit();
//                        limit_count.setText("第" + limitCount + "页");
//                    }
//                } catch (Exception e) {
//                }
//            } else if (id == R.id.right_message) {
//                //如果第一页不满的时候点击下一页不管用
//                //这里一次请求25条的
//                if (oList.size() <= 8) {
//                    limitCount = 1;
//                    limit_count.setText("第" + limitCount + "页");
//                    SharedPreferences limit = getActivity().getSharedPreferences("save_limit", Context.MODE_PRIVATE);
//                    limit.edit().putInt("key", limitCount).commit();
//                } else {
//                    limitCount++;
////                    //这里有问题呢还    因为第一页默认加载25条  可以判断当第一页时返回的数据严重少于25条时 即使在加载也没有数据了 因为没有那么多
////                    if (limitCount * 8 < oList.size()) {
////                        List<MessageListItem> mList = new ArrayList<>();
////                        if (oList.size() - 8 * limitCount < 8) {
////                            for (int i = (limitCount - 1) * 8; i < oList.size(); i++) {
////                                MessageListItem messageListItem = oList.get(i);
////                                mList.add(messageListItem);
////                            }
////                        } else {
////                            for (int i = (limitCount - 1) * 8; i < 8 * limitCount; i++) {
////                                MessageListItem messageListItem = oList.get(i);
////                                mList.add(messageListItem);
////                            }
////                        }
////                        this.adapter.setMessages(mList);
////                    } else {
////                        ll_bar.setVisibility(View.GONE);
////                        tv_loading.setVisibility(View.VISIBLE);
////                        tv_loading.setText(context.getString(R.string.status_loading_more));
////                        messagingController.loadMoreMessages(account, folderServerId, null);
////                    }
////                    limit_count.setText("第" + limitCount + "页");
//                    if (oList.size() % 8 == 0) {
//                        List<MessageListItem> mList = new ArrayList<>();
//                        //能整除 有10页
//                        int yeCount = oList.size() / 8;
//                        System.out.println("n可以被m整除");
//                        if (limitCount < yeCount) {
//                            for (int i = (limitCount - 1) * 8; i < 8 * limitCount; i++) {
//                                MessageListItem messageListItem = oList.get(i);
//                                mList.add(messageListItem);
//                            }
//                            this.adapter.setMessages(mList);
//                            SharedPreferences limit = getActivity().getSharedPreferences("save_limit", Context.MODE_PRIVATE);
//                            limit.edit().putInt("key", limitCount).commit();
//                            limit_count.setText("第" + limitCount + "页");
//                        } else if (limitCount == yeCount) {
//                            //没有更多数据了
//                            if (!currentFolder.moreMessages) {
//                                for (int i = (limitCount - 1) * 8; i < oList.size(); i++) {
//                                    MessageListItem messageListItem = oList.get(i);
//                                    mList.add(messageListItem);
//                                }
//                                setSp(3);
//                                this.adapter.setMessages(mList);
//                                right_message_list.setVisibility(View.GONE);
//                                SharedPreferences limit = getActivity().getSharedPreferences("save_limit", Context.MODE_PRIVATE);
//                                limit.edit().putInt("key", limitCount).commit();
//                                limit_count.setText("没有更多数据了");
//                            }
//                        } else {
//                            //没有更多数据了
//                            if (currentFolder.moreMessages) {
//                                setSp(1);
//                                ll_bar.setVisibility(View.GONE);
//                                tv_loading.setVisibility(View.VISIBLE);
//                                tv_loading.setText(context.getString(R.string.status_loading_more));
//                                messagingController.loadMoreMessages(account, folderServerId, null);
//                            }
//                        }
//                    } else {
//                        List<MessageListItem> mList = new ArrayList<>();
//                        System.out.println("n不能被m整除");
//                        //不能整除说明最后一页不能铺满 不能铺满加载铺满
//                        int yeCount = oList.size() / 8 + 1;
//                        if (limitCount <= yeCount - 1) {
//                            for (int i = (limitCount - 1) * 8; i < 8 * limitCount; i++) {
//                                MessageListItem messageListItem = oList.get(i);
//                                mList.add(messageListItem);
//                            }
//                            this.adapter.setMessages(mList);
//                            SharedPreferences limit = getActivity().getSharedPreferences("save_limit", Context.MODE_PRIVATE);
//                            limit.edit().putInt("key", limitCount).commit();
//                            limit_count.setText("第" + limitCount + "页");
//                        } else {
//                            //没有更多数据了
//                            if (!currentFolder.moreMessages) {
//                                for (int i = (limitCount - 1) * 8; i < oList.size(); i++) {
//                                    MessageListItem messageListItem = oList.get(i);
//                                    mList.add(messageListItem);
//                                }
//                                setSp(3);
//                                this.adapter.setMessages(mList);
//                                right_message_list.setVisibility(View.GONE);
//                                SharedPreferences limit = getActivity().getSharedPreferences("save_limit", Context.MODE_PRIVATE);
//                                limit.edit().putInt("key", limitCount).commit();
//                                limit_count.setText("没有更多数据了");
//                            } else {
//                                setSp(2);
//                                ll_bar.setVisibility(View.GONE);
//                                tv_loading.setVisibility(View.VISIBLE);
//                                tv_loading.setText(context.getString(R.string.status_loading_more));
//                                messagingController.loadMoreMessages(account, folderServerId, null);
//                            }
//                        }
//                    }
//                }
//            }
//        } else {
//            Toast.makeText(getActivity(), "请连接网络", Toast.LENGTH_SHORT).show();
//        }
//    }

    private void setSp(int i) {
        SharedPreferences sp = context.getSharedPreferences("dblist", Context.MODE_PRIVATE);
        sp.edit().putInt("key", oList.size()).putInt("int", i).commit();

    }

    class MessageListActivityListener extends SimpleMessagingListener {
        private final Object lock = new Object();

        @GuardedBy("lock")
        private int folderCompleted = 0;
        @GuardedBy("lock")
        private int folderTotal = 0;


        @Override
        public void remoteSearchFailed(String folderServerId, final String err) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    Activity activity = getActivity();
                    if (activity != null) {
                        Toast.makeText(activity, R.string.remote_search_error,
                                Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        @Override
        public void remoteSearchStarted(String folder) {
            handler.progress(true);
            handler.updateFooter(context.getString(R.string.remote_search_sending_query));
        }

        @Override
        public void enableProgressIndicator(boolean enable) {
            handler.progress(enable);
        }

        @Override
        public void remoteSearchFinished(String folderServerId, int numResults, int maxResults, List<String> extraResults) {
            handler.progress(false);
            handler.remoteSearchFinished();
            extraSearchResults = extraResults;
            if (extraResults != null && extraResults.size() > 0) {
                handler.updateFooter(String.format(context.getString(R.string.load_more_messages_fmt), maxResults));
            } else {
                handler.updateFooter(null);
            }
        }

        @Override
        public void remoteSearchServerQueryComplete(String folderServerId, int numResults, int maxResults) {
            handler.progress(true);
            if (maxResults != 0 && numResults > maxResults) {
                handler.updateFooter(context.getResources().getQuantityString(R.plurals.remote_search_downloading_limited,
                        maxResults, maxResults, numResults));
            } else {
                handler.updateFooter(context.getResources().getQuantityString(R.plurals.remote_search_downloading,
                        numResults, numResults));
            }

            informUserOfStatus();
        }

        private void informUserOfStatus() {
            handler.refreshTitle();
        }

        @Override
        public void synchronizeMailboxStarted(Account account, String folderServerId) {
            if (updateForMe(account, folderServerId)) {
                handler.progress(true);
                handler.folderLoading(folderServerId, true);

                synchronized (lock) {
                    folderCompleted = 0;
                    folderTotal = 0;
                }

                informUserOfStatus();
            }
        }

        @Override
        public void synchronizeMailboxHeadersProgress(Account account, String folderServerId, int completed, int total) {
            synchronized (lock) {
                folderCompleted = completed;
                folderTotal = total;
            }

            informUserOfStatus();
        }

        @Override
        public void synchronizeMailboxHeadersFinished(Account account, String folderServerId, int total, int completed) {
            synchronized (lock) {
                folderCompleted = 0;
                folderTotal = 0;
            }

            informUserOfStatus();
        }

        @Override
        public void synchronizeMailboxProgress(Account account, String folderServerId, int completed, int total) {
            synchronized (lock) {
                folderCompleted = completed;
                folderTotal = total;
            }

            informUserOfStatus();
        }

        @Override
        public void synchronizeMailboxFinished(Account account, String folderServerId) {
            if (updateForMe(account, folderServerId)) {
                handler.progress(false);
                handler.folderLoading(folderServerId, false);
            }
        }

        @Override
        public void synchronizeMailboxFailed(Account account, String folderServerId, String message) {

            if (updateForMe(account, folderServerId)) {
                handler.progress(false);
                handler.folderLoading(folderServerId, false);
            }
        }

        private boolean updateForMe(Account account, String folderServerId) {
            if (account == null || folderServerId == null) {
                return false;
            }

            if (!Utility.arrayContains(accountUuids, account.getUuid())) {
                return false;
            }

            List<String> folderServerIds = search.getFolderServerIds();
            return (folderServerIds.isEmpty() || folderServerIds.contains(folderServerId));
        }

        public int getFolderCompleted() {
            synchronized (lock) {
                return folderCompleted;
            }
        }

        public int getFolderTotal() {
            synchronized (lock) {
                return folderTotal;
            }
        }
    }


    private View getFooterView(ViewGroup parent) {
        if (footerView == null) {
            footerView = layoutInflater.inflate(R.layout.message_list_item_footer_two, parent, false);
            FooterViewHolder holder = new FooterViewHolder();
            holder.main = footerView.findViewById(R.id.main_text);
            footerView.setTag(holder);
        }

        return footerView;
    }

    private void updateFooterView() {
        if (!search.isManualSearch() && currentFolder != null && account != null) {
            if (currentFolder.loading) {
                updateFooter(context.getString(R.string.status_loading_more));
            } else if (!currentFolder.moreMessages) {
                updateFooter(null);
            } else {
                String message;
                if (account.getDisplayCount() == 0) {
                    message = context.getString(R.string.message_list_load_more_messages_action);
                } else {
                    message = String.format(context.getString(R.string.load_more_messages_fmt),
                            account.getDisplayCount());
                }
                //还原
//                updateFooter(message);
            }
        } else {
            //还原
//            updateFooter(null);
        }
    }

    public void updateFooter(final String text) {
        if (footerView == null) {
            return;
        }

        FooterViewHolder holder = (FooterViewHolder) footerView.getTag();

        if (text != null) {
            holder.main.setText(text);
            holder.main.setVisibility(View.VISIBLE);
        } else {
            holder.main.setVisibility(View.GONE);
        }
    }

    static class FooterViewHolder {
        public TextView main;
    }

    /**
     * Set selection state for all messages.
     *
     * @param selected If {@code true} all messages get selected. Otherwise, all messages get deselected and
     *                 action mode is finished.
     */
    private void setSelectionState(boolean selected) {
        if (selected) {
            if (adapter.getCount() == 0) {
                // Nothing to do if there are no messages
                return;
            }

            selectedCount = 0;
            for (int i = 0, end = adapter.getCount(); i < end; i++) {
                MessageListItem messageListItem = adapter.getItem(i);
                long uniqueId = messageListItem.getUniqueId();
                this.selected.add(uniqueId);

                if (showingThreadedList) {
                    int threadCount = messageListItem.getThreadCount();
                    selectedCount += (threadCount > 1) ? threadCount : 1;
                } else {
                    selectedCount++;
                }
            }

            if (actionMode == null) {
                startAndPrepareActionMode();
            }
            computeBatchDirection();
            updateActionModeTitle();
            computeSelectAllVisibility();
        } else {
            this.selected.clear();
            selectedCount = 0;
            if (actionMode != null) {
                actionMode.finish();
                actionMode = null;
            }
        }

        adapter.notifyDataSetChanged();
    }

    private void toggleMessageSelect(int listViewPosition) {
        int adapterPosition = listViewToAdapterPosition(listViewPosition);
        if (adapterPosition == AdapterView.INVALID_POSITION) {
            return;
        }

        toggleMessageSelectWithAdapterPosition(adapterPosition);
    }

    @Override
    public void toggleMessageFlagWithAdapterPosition(int adapterPosition) {
        MessageListItem messageListItem = adapter.getItem(adapterPosition);
        boolean flagged = messageListItem.isStarred();

        setFlag(adapterPosition, Flag.FLAGGED, !flagged);
    }

    private void toggleMessageSelectWithAdapterPosition(int adapterPosition) {
        MessageListItem messageListItem = adapter.getItem(adapterPosition);
        long uniqueId = messageListItem.getUniqueId();

        boolean selected = this.selected.contains(uniqueId);
        if (!selected) {
            this.selected.add(uniqueId);
        } else {
            this.selected.remove(uniqueId);
        }

        int selectedCountDelta = 1;
        if (showingThreadedList) {
            int threadCount = messageListItem.getThreadCount();
            if (threadCount > 1) {
                selectedCountDelta = threadCount;
            }
        }

        if (actionMode != null) {
            if (selectedCount == selectedCountDelta && selected) {
                actionMode.finish();
                actionMode = null;
                return;
            }
        } else {
            startAndPrepareActionMode();
        }

        if (selected) {
            selectedCount -= selectedCountDelta;
        } else {
            selectedCount += selectedCountDelta;
        }

        computeBatchDirection();
        updateActionModeTitle();

        computeSelectAllVisibility();

        adapter.notifyDataSetChanged();
    }

    private void updateActionModeTitle() {
        actionMode.setTitle(String.format(getString(R.string.actionbar_selected), selectedCount));
    }

    private void computeSelectAllVisibility() {
        actionModeCallback.showSelectAll(selected.size() != adapter.getCount());
    }

    private void computeBatchDirection() {
        boolean isBatchFlag = false;
        boolean isBatchRead = false;

        for (int i = 0, end = adapter.getCount(); i < end; i++) {
            MessageListItem messageListItem = adapter.getItem(i);
            long uniqueId = messageListItem.getUniqueId();

            if (selected.contains(uniqueId)) {
                boolean read = messageListItem.isRead();
                boolean flagged = messageListItem.isStarred();

                if (!flagged) {
                    isBatchFlag = true;
                }
                if (!read) {
                    isBatchRead = true;
                }

                if (isBatchFlag && isBatchRead) {
                    break;
                }
            }
        }

        actionModeCallback.showMarkAsRead(isBatchRead);
        actionModeCallback.showFlag(isBatchFlag);
    }

    private void setFlag(int adapterPosition, final Flag flag, final boolean newState) {
        if (adapterPosition == AdapterView.INVALID_POSITION) {
            return;
        }

        MessageListItem messageListItem = adapter.getItem(adapterPosition);
        Account account = messageListItem.getAccount();

        if (showingThreadedList && messageListItem.getThreadCount() > 1) {
            long threadRootId = messageListItem.getThreadRoot();
            messagingController.setFlagForThreads(account,
                    Collections.singletonList(threadRootId), flag, newState);
        } else {
            long id = messageListItem.getDatabaseId();
            messagingController.setFlag(account, Collections.singletonList(id), flag,
                    newState);
        }

        computeBatchDirection();
    }

    private void setFlagForSelected(final Flag flag, final boolean newState) {
        if (selected.isEmpty()) {
            return;
        }

        Map<Account, List<Long>> messageMap = new HashMap<>();
        Map<Account, List<Long>> threadMap = new HashMap<>();
        Set<Account> accounts = new HashSet<>();

        for (int position = 0, end = adapter.getCount(); position < end; position++) {
            MessageListItem messageListItem = adapter.getItem(position);
            long uniqueId = messageListItem.getUniqueId();

            if (selected.contains(uniqueId)) {
                Account account = messageListItem.getAccount();
                accounts.add(account);

                if (showingThreadedList && messageListItem.getThreadCount() > 1) {
                    List<Long> threadRootIdList = threadMap.get(account);
                    if (threadRootIdList == null) {
                        threadRootIdList = new ArrayList<>();
                        threadMap.put(account, threadRootIdList);
                    }

                    threadRootIdList.add(messageListItem.getThreadRoot());
                } else {
                    List<Long> messageIdList = messageMap.get(account);
                    if (messageIdList == null) {
                        messageIdList = new ArrayList<>();
                        messageMap.put(account, messageIdList);
                    }

                    messageIdList.add(messageListItem.getDatabaseId());
                }
            }
        }

        for (Account account : accounts) {
            List<Long> messageIds = messageMap.get(account);
            List<Long> threadRootIds = threadMap.get(account);

            if (messageIds != null) {
                messagingController.setFlag(account, messageIds, flag, newState);
            }

            if (threadRootIds != null) {
                messagingController.setFlagForThreads(account, threadRootIds, flag, newState);
            }
        }

        computeBatchDirection();
    }

    private void onMove(MessageReference message) {
        onMove(Collections.singletonList(message));
    }

    /**
     * Display the message move activity.
     *
     * @param messages Never {@code null}.
     */
    private void onMove(List<MessageReference> messages) {
        if (!checkCopyOrMovePossible(messages, FolderOperation.MOVE)) {
            return;
        }

        String folderServerId;
        if (isThreadDisplay) {
            folderServerId = messages.get(0).getFolderServerId();
        } else if (singleFolderMode) {
            folderServerId = currentFolder.serverId;
        } else {
            folderServerId = null;
        }


        displayFolderChoice(ACTIVITY_CHOOSE_FOLDER_MOVE, folderServerId,
                messages.get(0).getAccountUuid(), null,
                messages);
    }

    private void onCopy(MessageReference message) {
        onCopy(Collections.singletonList(message));
    }

    /**
     * Display the message copy activity.
     *
     * @param messages Never {@code null}.
     */
    private void onCopy(List<MessageReference> messages) {
        if (!checkCopyOrMovePossible(messages, FolderOperation.COPY)) {
            return;
        }

        String folderServerId;
        if (isThreadDisplay) {
            folderServerId = messages.get(0).getFolderServerId();
        } else if (singleFolderMode) {
            folderServerId = currentFolder.serverId;
        } else {
            folderServerId = null;
        }

        displayFolderChoice(ACTIVITY_CHOOSE_FOLDER_COPY, folderServerId,
                messages.get(0).getAccountUuid(),
                null,
                messages);
    }

    private void onDebugClearLocally(MessageReference message) {
        messagingController.debugClearMessagesLocally(Collections.singletonList(message));
    }

    /**
     * Helper method to manage the invocation of {@link #startActivityForResult(Intent, int)} for a
     * folder operation ({@link ChooseFolderActivity} activity), while saving a list of associated messages.
     *
     * @param requestCode If {@code >= 0}, this code will be returned in {@code onActivityResult()} when the
     *                    activity exits.
     * @see #startActivityForResult(Intent, int)
     */
    private void displayFolderChoice(int requestCode, String sourceFolder,
                                     String accountUuid, String lastSelectedFolder,
                                     List<MessageReference> messages) {
        Intent intent = ChooseFolderActivity.buildLaunchIntent(requireContext(), accountUuid, sourceFolder,
                lastSelectedFolder, false, null);

        // remember the selected messages for #onActivityResult
        activeMessages = messages;
        startActivityForResult(intent, requestCode);
    }

    private void onArchive(MessageReference message) {
        onArchive(Collections.singletonList(message));
    }

    private void onArchive(final List<MessageReference> messages) {
        Map<Account, List<MessageReference>> messagesByAccount = groupMessagesByAccount(messages);

        for (Entry<Account, List<MessageReference>> entry : messagesByAccount.entrySet()) {
            Account account = entry.getKey();
            String archiveFolder = account.getArchiveFolder();

            if (archiveFolder != null) {
                move(entry.getValue(), archiveFolder);
            }
        }
    }

    private Map<Account, List<MessageReference>> groupMessagesByAccount(final List<MessageReference> messages) {
        Map<Account, List<MessageReference>> messagesByAccount = new HashMap<>();
        for (MessageReference message : messages) {
            Account account = preferences.getAccount(message.getAccountUuid());

            List<MessageReference> msgList = messagesByAccount.get(account);
            if (msgList == null) {
                msgList = new ArrayList<>();
                messagesByAccount.put(account, msgList);
            }

            msgList.add(message);
        }
        return messagesByAccount;
    }

    private void onSpam(MessageReference message) {
        onSpam(Collections.singletonList(message));
    }

    /**
     * Move messages to the spam folder.
     *
     * @param messages The messages to move to the spam folder. Never {@code null}.
     */
    private void onSpam(List<MessageReference> messages) {
        if (K9.isConfirmSpam()) {
            // remember the message selection for #onCreateDialog(int)
            activeMessages = messages;
            showDialog(R.id.dialog_confirm_spam);
        } else {
            onSpamConfirmed(messages);
        }
    }

    private void onSpamConfirmed(List<MessageReference> messages) {
        Map<Account, List<MessageReference>> messagesByAccount = groupMessagesByAccount(messages);

        for (Entry<Account, List<MessageReference>> entry : messagesByAccount.entrySet()) {
            Account account = entry.getKey();
            String spamFolder = account.getSpamFolder();

            if (spamFolder != null) {
                move(entry.getValue(), spamFolder);
            }
        }
    }

    private enum FolderOperation {
        COPY, MOVE
    }

    /**
     * Display a Toast message if any message isn't synchronized
     *
     * @param messages  The messages to copy or move. Never {@code null}.
     * @param operation The type of operation to perform. Never {@code null}.
     * @return {@code true}, if operation is possible.
     */
    private boolean checkCopyOrMovePossible(final List<MessageReference> messages,
                                            final FolderOperation operation) {

        if (messages.isEmpty()) {
            return false;
        }

        boolean first = true;
        for (MessageReference message : messages) {
            if (first) {
                first = false;
                Account account = preferences.getAccount(message.getAccountUuid());
                if ((operation == FolderOperation.MOVE && !messagingController.isMoveCapable(account)) ||
                        (operation == FolderOperation.COPY && !messagingController.isCopyCapable(account))) {
                    return false;
                }
            }
            // message check
            if ((operation == FolderOperation.MOVE && !messagingController.isMoveCapable(message)) ||
                    (operation == FolderOperation.COPY && !messagingController.isCopyCapable(message))) {
                final Toast toast = Toast.makeText(getActivity(), R.string.move_copy_cannot_copy_unsynced_message,
                        Toast.LENGTH_LONG);
                toast.show();
                return false;
            }
        }
        return true;
    }

    /**
     * Copy the specified messages to the specified folder.
     *
     * @param messages    List of messages to copy. Never {@code null}.
     * @param destination The name of the destination folder. Never {@code null}.
     */
    private void copy(List<MessageReference> messages, final String destination) {
        copyOrMove(messages, destination, FolderOperation.COPY);
    }

    /**
     * Move the specified messages to the specified folder.
     *
     * @param messages    The list of messages to move. Never {@code null}.
     * @param destination The name of the destination folder. Never {@code null}.
     */
    private void move(List<MessageReference> messages, final String destination) {
        copyOrMove(messages, destination, FolderOperation.MOVE);
    }

    /**
     * The underlying implementation for {@link #copy(List, String)} and
     * {@link #move(List, String)}. This method was added mainly because those 2
     * methods share common behavior.
     *
     * @param messages    The list of messages to copy or move. Never {@code null}.
     * @param destination The name of the destination folder. Never {@code null}.
     * @param operation   Specifies what operation to perform. Never {@code null}.
     */
    private void copyOrMove(List<MessageReference> messages, final String destination,
                            final FolderOperation operation) {

        Map<String, List<MessageReference>> folderMap = new HashMap<>();

        for (MessageReference message : messages) {
            if ((operation == FolderOperation.MOVE && !messagingController.isMoveCapable(message)) ||
                    (operation == FolderOperation.COPY && !messagingController.isCopyCapable(message))) {

                Toast.makeText(getActivity(), R.string.move_copy_cannot_copy_unsynced_message,
                        Toast.LENGTH_LONG).show();

                // XXX return meaningful error value?

                // message isn't synchronized
                return;
            }

            String folderServerId = message.getFolderServerId();
            if (folderServerId.equals(destination)) {
                // Skip messages already in the destination folder
                continue;
            }

            List<MessageReference> outMessages = folderMap.get(folderServerId);
            if (outMessages == null) {
                outMessages = new ArrayList<>();
                folderMap.put(folderServerId, outMessages);
            }

            outMessages.add(message);
        }

        for (Map.Entry<String, List<MessageReference>> entry : folderMap.entrySet()) {
            String folderServerId = entry.getKey();
            List<MessageReference> outMessages = entry.getValue();
            Account account = preferences.getAccount(outMessages.get(0).getAccountUuid());

            if (operation == FolderOperation.MOVE) {
                if (showingThreadedList) {
                    messagingController.moveMessagesInThread(account, folderServerId, outMessages, destination);
                } else {
                    messagingController.moveMessages(account, folderServerId, outMessages, destination);
                }
            } else {
                if (showingThreadedList) {
                    messagingController.copyMessagesInThread(account, folderServerId, outMessages, destination);
                } else {
                    messagingController.copyMessages(account, folderServerId, outMessages, destination);
                }
            }
        }
    }


    class ActionModeCallback implements ActionMode.Callback {
        private MenuItem mSelectAll;
        private MenuItem mMarkAsRead;
        private MenuItem mMarkAsUnread;
        private MenuItem mFlag;
        private MenuItem mUnflag;

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            mSelectAll = menu.findItem(R.id.select_all);
            mMarkAsRead = menu.findItem(R.id.mark_as_read);
            mMarkAsUnread = menu.findItem(R.id.mark_as_unread);
            mFlag = menu.findItem(R.id.flag);
            mUnflag = menu.findItem(R.id.unflag);

            // we don't support cross account actions atm
            if (!singleAccountMode) {
                // show all
                menu.findItem(R.id.move).setVisible(true);
                menu.findItem(R.id.archive).setVisible(true);
                menu.findItem(R.id.spam).setVisible(true);
                menu.findItem(R.id.copy).setVisible(true);

                Set<String> accountUuids = getAccountUuidsForSelected();

                for (String accountUuid : accountUuids) {
                    Account account = preferences.getAccount(accountUuid);
                    if (account != null) {
                        setContextCapabilities(account, menu);
                    }
                }

            }
            return true;
        }

        /**
         * Get the set of account UUIDs for the selected messages.
         */
        private Set<String> getAccountUuidsForSelected() {
            int maxAccounts = accountUuids.length;
            Set<String> accountUuids = new HashSet<>(maxAccounts);

            for (int position = 0, end = adapter.getCount(); position < end; position++) {
                MessageListItem messageListItem = adapter.getItem(position);
                long uniqueId = messageListItem.getUniqueId();

                if (selected.contains(uniqueId)) {
                    String accountUuid = messageListItem.getAccount().getUuid();
                    accountUuids.add(accountUuid);

                    if (accountUuids.size() == MessageListFragment.this.accountUuids.length) {
                        break;
                    }
                }
            }

            return accountUuids;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            actionMode = null;
            mSelectAll = null;
            mMarkAsRead = null;
            mMarkAsUnread = null;
            mFlag = null;
            mUnflag = null;
            setSelectionState(false);
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.message_list_context, menu);

            // check capabilities
            setContextCapabilities(account, menu);

            return true;
        }

        /**
         * Disables menu options not supported by the account type or current "search view".
         *
         * @param account The account to query for its capabilities.
         * @param menu    The menu to adapt.
         */
        private void setContextCapabilities(Account account, Menu menu) {
            if (!singleAccountMode) {
                // We don't support cross-account copy/move operations right now
                menu.findItem(R.id.move).setVisible(false);
                menu.findItem(R.id.copy).setVisible(false);

                //TODO: we could support the archive and spam operations if all selected messages
                // belong to non-POP3 accounts
                menu.findItem(R.id.archive).setVisible(false);
                menu.findItem(R.id.spam).setVisible(false);

            } else {
                // hide unsupported
                if (!messagingController.isCopyCapable(account)) {
                    menu.findItem(R.id.copy).setVisible(false);
                }

                if (!messagingController.isMoveCapable(account)) {
                    menu.findItem(R.id.move).setVisible(false);
                    menu.findItem(R.id.archive).setVisible(false);
                    menu.findItem(R.id.spam).setVisible(false);
                }

                if (!account.hasArchiveFolder()) {
                    menu.findItem(R.id.archive).setVisible(false);
                }

                if (!account.hasSpamFolder()) {
                    menu.findItem(R.id.spam).setVisible(false);
                }
            }
        }

        public void showSelectAll(boolean show) {
            if (actionMode != null) {
                mSelectAll.setVisible(show);
            }
        }

        public void showMarkAsRead(boolean show) {
            if (actionMode != null) {
                mMarkAsRead.setVisible(show);
                mMarkAsUnread.setVisible(!show);
            }
        }

        public void showFlag(boolean show) {
            if (actionMode != null) {
                mFlag.setVisible(show);
                mUnflag.setVisible(!show);
            }
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            /*
             * In the following we assume that we can't move or copy
             * mails to the same folder. Also that spam isn't available if we are
             * in the spam folder,same for archive.
             *
             * This is the case currently so safe assumption.
             */
            int id = item.getItemId();
            if (id == R.id.delete) {
                List<MessageReference> messages = getCheckedMessages();
                onDelete(messages);
                selectedCount = 0;
            } else if (id == R.id.mark_as_read) {
                setFlagForSelected(Flag.SEEN, true);
            } else if (id == R.id.mark_as_unread) {
                setFlagForSelected(Flag.SEEN, false);
            } else if (id == R.id.flag) {
                setFlagForSelected(Flag.FLAGGED, true);
            } else if (id == R.id.unflag) {
                setFlagForSelected(Flag.FLAGGED, false);
            } else if (id == R.id.select_all) {
                selectAll();
            } else if (id == R.id.archive) {    // only if the account supports this
                onArchive(getCheckedMessages());
                selectedCount = 0;
            } else if (id == R.id.spam) {
                onSpam(getCheckedMessages());
                selectedCount = 0;
            } else if (id == R.id.move) {
                onMove(getCheckedMessages());
                selectedCount = 0;
            } else if (id == R.id.copy) {
                onCopy(getCheckedMessages());
                selectedCount = 0;
            }

            if (selectedCount == 0) {
                actionMode.finish();
            }

            return true;
        }
    }

    @Override
    public void doPositiveClick(int dialogId, Account account) {
        if (dialogId == R.id.dialog_confirm_spam) {
            onSpamConfirmed(activeMessages);
            // No further need for this reference
            activeMessages = null;
        } else if (dialogId == R.id.dialog_confirm_delete) {
            onDeleteConfirmed(activeMessages);
            activeMessage = null;
            if (adapter != null) adapter.setActiveMessage(null);
        } else if (dialogId == R.id.dialog_confirm_mark_all_as_read) {
            markAllAsRead();
        } else if (dialogId == R.id.dialog_confirm_empty_trash) {
            messagingController.emptyTrash(account, null);
        }
    }

    @Override
    public void doNegativeClick(int dialogId, Account account) {
        if (dialogId == R.id.dialog_confirm_spam || dialogId == R.id.dialog_confirm_delete) {
            // No further need for this reference
            activeMessages = null;
        }
    }

    @Override
    public void dialogCancelled(int dialogId, Account account) {

        doNegativeClick(dialogId, account);
    }

    public void checkMail() {
        if (isSingleAccountMode() && isSingleFolderMode()) {
            messagingController.synchronizeMailbox(account, folderServerId, activityListener);
            messagingController.sendPendingMessages(account, activityListener);
        } else if (allAccounts) {
            messagingController.checkMail(context, null, true, true, activityListener);
        } else {
            for (String accountUuid : accountUuids) {
                Account account = preferences.getAccount(accountUuid);
                messagingController.checkMail(context, account, true, true, activityListener);
            }
        }
    }

    /**
     * We need to do some special clean up when leaving a remote search result screen. If no
     * remote search is in progress, this method does nothing special.
     */
    @Override
    public void onStop() {
        // If we represent a remote search, then kill that before going back.
        if (isRemoteSearch() && remoteSearchFuture != null) {
            try {
                Timber.i("Remote search in progress, attempting to abort...");
                // Canceling the future stops any message fetches in progress.
                final boolean cancelSuccess = remoteSearchFuture.cancel(true);   // mayInterruptIfRunning = true
                if (!cancelSuccess) {
                    Timber.e("Could not cancel remote search future.");
                }
                // Closing the folder will kill off the connection if we're mid-search.
                final Account searchAccount = account;
                // Send a remoteSearchFinished() message for good measure.
                activityListener
                        .remoteSearchFinished(currentFolder.serverId, 0, searchAccount.getRemoteSearchNumResults(), null);
            } catch (Exception e) {
                // Since the user is going back, log and squash any exceptions.
                Timber.e(e, "Could not abort remote search before going back");
            }
        }

        // Workaround for Android bug https://issuetracker.google.com/issues/37008170
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(false);
            swipeRefreshLayout.destroyDrawingCache();
            swipeRefreshLayout.clearAnimation();
        }

        super.onStop();
    }

    public void selectAll() {
        setSelectionState(true);
    }

    public void onMoveUp() {
        int currentPosition = listView.getSelectedItemPosition();
        if (currentPosition == AdapterView.INVALID_POSITION || listView.isInTouchMode()) {
            currentPosition = listView.getFirstVisiblePosition();
        }
        if (currentPosition > 0) {
            listView.setSelection(currentPosition - 1);
        }
    }

    public void onMoveDown() {
        int currentPosition = listView.getSelectedItemPosition();
        if (currentPosition == AdapterView.INVALID_POSITION || listView.isInTouchMode()) {
            currentPosition = listView.getFirstVisiblePosition();
        }

        if (currentPosition < listView.getCount()) {
            listView.setSelection(currentPosition + 1);
        }
    }

    public boolean openPrevious(MessageReference messageReference) {
        int position = getPosition(messageReference);
        if (position <= 0) {
            return false;
        }

        openMessageAtPosition(position - 1);
        return true;
    }

    public boolean openNext(MessageReference messageReference) {
        int position = getPosition(messageReference);
        if (position < 0 || position == adapter.getCount() - 1) {
            return false;
        }

        openMessageAtPosition(position + 1);
        return true;
    }

    public boolean isFirst(MessageReference messageReference) {
        return adapter.isEmpty() || messageReference.equals(getReferenceForPosition(0));
    }

    public boolean isLast(MessageReference messageReference) {
        return adapter.isEmpty() || messageReference.equals(getReferenceForPosition(adapter.getCount() - 1));
    }

    private MessageReference getReferenceForPosition(int position) {
        MessageListItem messageListItem = adapter.getItem(position);

        String accountUuid = messageListItem.getAccount().getUuid();
        String folderServerId = messageListItem.getFolderServerId();
        String messageUid = messageListItem.getMessageUid();
        return new MessageReference(accountUuid, folderServerId, messageUid, null);
    }

    private void openMessageAtPosition(int position) {
        // Scroll message into view if necessary
        int listViewPosition = adapterToListViewPosition(position);
        if (listViewPosition != AdapterView.INVALID_POSITION &&
                (listViewPosition < listView.getFirstVisiblePosition() ||
                        listViewPosition > listView.getLastVisiblePosition())) {
            listView.setSelection(listViewPosition);
        }

        MessageReference ref = getReferenceForPosition(position);

        // For some reason the listView.setSelection() above won't do anything when we call
        // onOpenMessage() (and consequently adapter.notifyDataSetChanged()) right away. So we
        // defer the call using MessageListHandler.
        handler.openMessage(ref);
    }

    private int getPosition(MessageReference messageReference) {
        for (int i = 0, len = adapter.getCount(); i < len; i++) {
            MessageListItem messageListItem = adapter.getItem(i);

            String accountUuid = messageListItem.getAccount().getUuid();
            String folderServerId = messageListItem.getFolderServerId();
            String uid = messageListItem.getMessageUid();

            if (accountUuid.equals(messageReference.getAccountUuid()) &&
                    folderServerId.equals(messageReference.getFolderServerId()) &&
                    uid.equals(messageReference.getUid())) {
                return i;
            }
        }

        return -1;
    }

    public interface MessageListFragmentListener {
        int MAX_PROGRESS = 10000;

        void setMessageListProgressEnabled(boolean enable);

        void setMessageListProgress(int level);

        void showThread(Account account, String folderServerId, long rootId);

        void showMoreFromSameSender(String senderAddress);

        void onResendMessage(MessageReference message);

        void onForward(MessageReference message);

        void onForwardAsAttachment(MessageReference message);

        void onReply(MessageReference message);

        void onReplyAll(MessageReference message);

        void openMessage(MessageReference messageReference);

        void setMessageListTitle(String title);

        //显示Actionbar
        void setActionbar(Boolean b);

        void onCompose(Account account);

        boolean startSearch(Account account, String folderServerId);

        void remoteSearchStarted();

        void goBack();

        void updateMenu();

    }

    public void onReverseSort() {
        changeSort(sortType);
    }

    private MessageReference getSelectedMessage() {
        int listViewPosition = listView.getSelectedItemPosition();
        int adapterPosition = listViewToAdapterPosition(listViewPosition);

        return getMessageAtPosition(adapterPosition);
    }

    private int getAdapterPositionForSelectedMessage() {
        int listViewPosition = listView.getSelectedItemPosition();
        return listViewToAdapterPosition(listViewPosition);
    }

    private int getPositionForUniqueId(long uniqueId) {
        for (int position = 0, end = adapter.getCount(); position < end; position++) {
            MessageListItem messageListItem = adapter.getItem(position);
            if (messageListItem.getUniqueId() == uniqueId) {
                return position;
            }
        }

        return AdapterView.INVALID_POSITION;
    }

    private MessageReference getMessageAtPosition(int adapterPosition) {
        if (adapterPosition == AdapterView.INVALID_POSITION) {
            return null;
        }

        MessageListItem messageListItem = adapter.getItem(adapterPosition);

        String accountUuid = messageListItem.getAccount().getUuid();
        String folderServerId = messageListItem.getFolderServerId();
        String messageUid = messageListItem.getMessageUid();

        return new MessageReference(accountUuid, folderServerId, messageUid, null);
    }

    private List<MessageReference> getCheckedMessages() {
        List<MessageReference> messages = new ArrayList<>(selected.size());
        for (int position = 0, end = adapter.getCount(); position < end; position++) {
            MessageListItem messageListItem = adapter.getItem(position);
            long uniqueId = messageListItem.getUniqueId();

            if (selected.contains(uniqueId)) {
                MessageReference message = getMessageAtPosition(position);
                if (message != null) {
                    messages.add(message);
                }
            }
        }

        return messages;
    }

    public void onDelete() {
        MessageReference message = getSelectedMessage();
        if (message != null) {
            onDelete(Collections.singletonList(message));
        }
    }

    public void toggleMessageSelect() {
        toggleMessageSelect(listView.getSelectedItemPosition());
    }

    public void onToggleFlagged() {
        onToggleFlag(Flag.FLAGGED);
    }

    public void onToggleRead() {
        onToggleFlag(Flag.SEEN);
    }

    private void onToggleFlag(Flag flag) {
        int adapterPosition = getAdapterPositionForSelectedMessage();
        if (adapterPosition == ListView.INVALID_POSITION) {
            return;
        }

        MessageListItem messageListItem = adapter.getItem(adapterPosition);
        boolean flagState = false;
        if (flag == Flag.SEEN) {
            flagState = messageListItem.isRead();
        } else if (flag == Flag.FLAGGED) {
            flagState = messageListItem.isStarred();
        }
        setFlag(adapterPosition, flag, !flagState);
    }

    public void onMove() {
        MessageReference message = getSelectedMessage();
        if (message != null) {
            onMove(message);
        }
    }

    public void onArchive() {
        MessageReference message = getSelectedMessage();
        if (message != null) {
            onArchive(message);
        }
    }

    public void onCopy() {
        MessageReference message = getSelectedMessage();
        if (message != null) {
            onCopy(message);
        }
    }

    public boolean isOutbox() {
        return (folderServerId != null && folderServerId.equals(account.getOutboxFolder()));
    }

    public boolean isRemoteFolder() {
        if (search.isManualSearch() || isOutbox()) {
            return false;
        }

        if (!messagingController.isMoveCapable(account)) {
            // For POP3 accounts only the Inbox is a remote folder.
            return (folderServerId != null && folderServerId.equals(account.getInboxFolder()));
        }

        return true;
    }

    public boolean isManualSearch() {
        return search.isManualSearch();
    }

    public boolean shouldShowExpungeAction() {
        return account != null && account.getExpungePolicy() == EXPUNGE_MANUALLY &&
                messagingController.supportsExpunge(account);
    }

    public void onRemoteSearch() {
        // Remote search is useless without the network.
        if (hasConnectivity) {
            onRemoteSearchRequested();
        } else {
            Toast.makeText(getActivity(), getText(R.string.remote_search_unavailable_no_network),
                    Toast.LENGTH_SHORT).show();
        }
    }

    public boolean isRemoteSearch() {
        return remoteSearchPerformed;
    }

    public boolean isRemoteSearchAllowed() {
        if (!search.isManualSearch() || remoteSearchPerformed || !singleFolderMode) {
            return false;
        }

        boolean allowRemoteSearch = false;
        final Account searchAccount = account;
        if (searchAccount != null) {
            allowRemoteSearch = searchAccount.isAllowRemoteSearch();
        }

        return allowRemoteSearch;
    }

    public boolean onSearchRequested() {
        String folderServerId = (currentFolder != null) ? currentFolder.serverId : null;
        return fragmentListener.startSearch(account, folderServerId);
    }

    public List<MessageListItem> removeRepeatFactor(List<MessageListItem> list1, List<MessageListItem> list2) throws Exception {
        if (list1 != null && list2 != null) {
            if (list1.size() != 0 && list2.size() != 0) {
                List<MessageListItem> A = new ArrayList(list1);
                List<MessageListItem> B = new ArrayList(list2);
                A.retainAll(B);
                if (A.size() != 0) {
                    B.removeAll(A);
                }
                return (List<MessageListItem>) B;
            }
        }
        return list2;
    }


    /**
     * @param firstArrayList  第一个ArrayList
     * @param secondArrayList 第二个ArrayList
     * @return resultList 差集ArrayList
     * @方法描述：获取两个ArrayList的差集
     */

    public static List<MessageListItem> receiveDefectList(List<MessageListItem> firstArrayList, List<MessageListItem> secondArrayList) {

        List<MessageListItem> resultList = new ArrayList<MessageListItem>();

        LinkedList<MessageListItem> result = new LinkedList<MessageListItem>(firstArrayList);// 大集合用linkedlist

        HashSet<MessageListItem> othHash = new HashSet<MessageListItem>(secondArrayList);// 小集合用hashset

        Iterator<MessageListItem> iter = result.iterator();// 采用Iterator迭代器进行数据的操作

        while (iter.hasNext()) {

            if (othHash.contains(iter.next())) {

                iter.remove();

            }

        }

        resultList = new ArrayList<MessageListItem>(result);

        return resultList;

    }

    private void SaveMessageList(List<MessageListItem> messageListItems) {
        SharedPreferences sp = context.getSharedPreferences(String.valueOf(limitCount), Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = sp.edit();
        String s = new Gson().toJson(messageListItems);
        edit.putString("key", s);
        edit.commit();
    }

    private String getSaveMessageList() {
        SharedPreferences sp = context.getSharedPreferences(String.valueOf(limitCount - 1), Context.MODE_PRIVATE);
        return sp.getString("key", null);
    }

    private String getSaveMessageList1(int limitCount) {
        SharedPreferences sp = context.getSharedPreferences(String.valueOf(limitCount), Context.MODE_PRIVATE);
        return sp.getString("key", null);
    }

    public boolean isLoadFinished() {
        return messageListLoaded;
    }

    /**
     * Close the context menu when the message it was opened for is no longer in the message list.
     */
    private void updateContextMenu(List<MessageListItem> messageListItems) {
        if (contextMenuUniqueId == 0) {
            return;
        }

        for (MessageListItem messageListItem : messageListItems) {
            if (messageListItem.getUniqueId() == contextMenuUniqueId) {
                return;
            }
        }

        contextMenuUniqueId = 0;
        Activity activity = getActivity();
        if (activity != null) {
            activity.closeContextMenu();
        }
    }

    private void cleanupSelected(List<MessageListItem> messageListItems) {
        if (selected.isEmpty()) {
            return;
        }

        Set<Long> selected = new HashSet<>();
        for (MessageListItem messageListItem : messageListItems) {
            long uniqueId = messageListItem.getUniqueId();
            if (this.selected.contains(uniqueId)) {
                selected.add(uniqueId);
            }
        }

        this.selected = selected;
    }

    /**
     * Starts or finishes the action mode when necessary.
     */
    private void resetActionMode() {
        if (selected.isEmpty()) {
            if (actionMode != null) {
                actionMode.finish();
            }
            return;
        }

        if (actionMode == null) {
            startAndPrepareActionMode();
        }

        recalculateSelectionCount();
        updateActionModeTitle();
    }

    private void startAndPrepareActionMode() {
        AppCompatActivity activity = (AppCompatActivity) requireActivity();
        ActionMode actionMode = activity.startSupportActionMode(actionModeCallback);
        this.actionMode = actionMode;
        if (actionMode != null) {
            actionMode.invalidate();
        }
    }

    /**
     * Recalculates the selection count.
     *
     * <p>
     * For non-threaded lists this is simply the number of visibly selected messages. If threaded
     * view is enabled this method counts the number of messages in the selected threads.
     * </p>
     */
    private void recalculateSelectionCount() {
        if (!showingThreadedList) {
            selectedCount = selected.size();
            return;
        }

        selectedCount = 0;
        for (int i = 0, end = adapter.getCount(); i < end; i++) {
            MessageListItem messageListItem = adapter.getItem(i);
            long uniqueId = messageListItem.getUniqueId();

            if (selected.contains(uniqueId)) {
                int threadCount = messageListItem.getThreadCount();
                selectedCount += (threadCount > 1) ? threadCount : 1;
            }
        }
    }

    void remoteSearchFinished() {
        remoteSearchFuture = null;
    }

    /**
     * Mark a message as 'active'.
     *
     * <p>
     * The active message is the one currently displayed in the message view portion of the split
     * view.
     * </p>
     *
     * @param messageReference {@code null} to not mark any message as being 'active'.
     */
    public void setActiveMessage(MessageReference messageReference) {
        activeMessage = messageReference;

        // Reload message list with modified query that always includes the active message
        if (isAdded()) {
            loadMessageList();
        }

        // Redraw list immediately
        if (adapter != null) {
            adapter.setActiveMessage(activeMessage);
            adapter.notifyDataSetChanged();
        }
    }

    public void setLimitCount() {
        limitCount = 1;
    }

    public boolean isSingleAccountMode() {
        return singleAccountMode;
    }

    public boolean isSingleFolderMode() {
        return singleFolderMode;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isMarkAllAsReadSupported() {
        return (isSingleAccountMode() && isSingleFolderMode());
    }

    public void confirmMarkAllAsRead() {
        if (K9.isConfirmMarkAllRead()) {
            showDialog(R.id.dialog_confirm_mark_all_as_read);
        } else {
            markAllAsRead();
        }
    }

    private void markAllAsRead() {
        if (isMarkAllAsReadSupported()) {
            messagingController.markAllMessagesRead(account, folderServerId);
        }
    }

    public boolean isCheckMailSupported() {
        return (allAccounts || !isSingleAccountMode() || !isSingleFolderMode() ||
                isRemoteFolder());
    }

    private boolean isCheckMailAllowed() {
        return (!isManualSearch() && isCheckMailSupported());
    }

    private boolean isPullToRefreshAllowed() {
        return (isRemoteSearchAllowed() || isCheckMailAllowed());
    }

    public LocalSearch getLocalSearch() {
        return search;
    }

    //TODO  加载数据
    private void loadMessageList() {

        Log.i("tag", "vvvvvvvvvvvvvv22222222");
        MessageListConfig config = new MessageListConfig(search, showingThreadedList, sortType, sortAscending,
                sortDateAscending, activeMessage);

        getViewModel().loadMessageList(config);

    }
}
