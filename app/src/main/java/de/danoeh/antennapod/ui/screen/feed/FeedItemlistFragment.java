package de.danoeh.antennapod.ui.screen.feed;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.LightingColorFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.util.Pair;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.snackbar.Snackbar;

import de.danoeh.antennapod.ui.CoverLoader;
import de.danoeh.antennapod.ui.screen.episode.ItemPagerFragment;
import de.danoeh.antennapod.ui.screen.SearchFragment;
import de.danoeh.antennapod.ui.TransitionEffect;
import de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManager;
import de.danoeh.antennapod.ui.screen.download.DownloadLogFragment;
import de.danoeh.antennapod.ui.screen.feed.preferences.FeedSettingsFragment;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.ui.episodeslist.EpisodeItemListAdapter;
import de.danoeh.antennapod.event.FeedEvent;
import de.danoeh.antennapod.ui.MenuItemUtils;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.ui.common.IntentUtils;
import de.danoeh.antennapod.ui.share.ShareUtils;
import de.danoeh.antennapod.ui.episodeslist.MoreContentListFooterUtil;
import de.danoeh.antennapod.databinding.FeedItemListFragmentBinding;
import de.danoeh.antennapod.ui.screen.download.DownloadLogDetailsDialog;
import de.danoeh.antennapod.ui.FeedItemFilterDialog;
import de.danoeh.antennapod.event.EpisodeDownloadEvent;
import de.danoeh.antennapod.event.FavoritesEvent;
import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.event.FeedListUpdateEvent;
import de.danoeh.antennapod.event.FeedUpdateRunningEvent;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.event.QueueEvent;
import de.danoeh.antennapod.event.UnreadItemsUpdateEvent;
import de.danoeh.antennapod.event.playback.PlaybackPositionEvent;
import de.danoeh.antennapod.ui.episodeslist.EpisodeMultiSelectActionHandler;
import de.danoeh.antennapod.ui.swipeactions.SwipeActions;
import de.danoeh.antennapod.ui.episodeslist.FeedItemMenuHandler;
import de.danoeh.antennapod.model.download.DownloadResult;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.glide.FastBlurTransformation;
import de.danoeh.antennapod.ui.episodeslist.EpisodeItemViewHolder;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/**
 * Displays a list of FeedItems.
 */
public class FeedItemlistFragment extends Fragment implements AdapterView.OnItemClickListener,
        MaterialToolbar.OnMenuItemClickListener, EpisodeItemListAdapter.OnSelectModeListener {
    public static final String TAG = "ItemlistFragment";
    private static final String ARGUMENT_FEED_ID = "argument.de.danoeh.antennapod.feed_id";
    private static final String KEY_UP_ARROW = "up_arrow";
    protected static final int EPISODES_PER_PAGE = 150;
    protected int page = 1;
    protected boolean isLoadingMore = false;
    protected boolean hasMoreItems = false;

    private FeedItemListAdapter adapter;
    private SwipeActions swipeActions;
    private MoreContentListFooterUtil nextPageLoader;
    private boolean displayUpArrow;
    private long feedID;
    private Feed feed;
    private boolean headerCreated = false;
    private Disposable disposable;
    private FeedItemListFragmentBinding viewBinding;

    /**
     * Creates new ItemlistFragment which shows the Feeditems of a specific
     * feed. Sets 'showFeedtitle' to false
     *
     * @param feedId The id of the feed to show
     * @return the newly created instance of an ItemlistFragment
     */
    public static FeedItemlistFragment newInstance(long feedId) {
        FeedItemlistFragment i = new FeedItemlistFragment();
        Bundle b = new Bundle();
        b.putLong(ARGUMENT_FEED_ID, feedId);
        i.setArguments(b);
        return i;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle args = getArguments();
        Validate.notNull(args);
        feedID = args.getLong(ARGUMENT_FEED_ID);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        viewBinding = FeedItemListFragmentBinding.inflate(inflater);
        viewBinding.toolbar.inflateMenu(R.menu.feedlist);
        viewBinding.toolbar.setOnMenuItemClickListener(this);
        viewBinding.toolbar.setOnLongClickListener(v -> {
            viewBinding.recyclerView.scrollToPosition(5);
            viewBinding.recyclerView.post(() -> viewBinding.recyclerView.smoothScrollToPosition(0));
            viewBinding.appBar.setExpanded(true);
            return false;
        });
        displayUpArrow = getParentFragmentManager().getBackStackEntryCount() != 0;
        if (savedInstanceState != null) {
            displayUpArrow = savedInstanceState.getBoolean(KEY_UP_ARROW);
        }
        ((MainActivity) getActivity()).setupToolbarToggle(viewBinding.toolbar, displayUpArrow);
        updateToolbar();
        setupLoadMoreScrollListener();

        viewBinding.recyclerView.setRecycledViewPool(((MainActivity) getActivity()).getRecycledViewPool());
        adapter = new FeedItemListAdapter((MainActivity) getActivity());
        adapter.setOnSelectModeListener(this);
        viewBinding.recyclerView.setAdapter(adapter);
        swipeActions = new SwipeActions(this, TAG).attachTo(viewBinding.recyclerView);
        viewBinding.progressBar.setVisibility(View.VISIBLE);

        ToolbarIconTintManager iconTintManager = new ToolbarIconTintManager(
                viewBinding.toolbar.getContext(), viewBinding.toolbar, viewBinding.collapsingToolbar) {
            @Override
            protected void doTint(Context themedContext) {
                viewBinding.toolbar.getMenu().findItem(R.id.refresh_item)
                        .setIcon(AppCompatResources.getDrawable(themedContext, R.drawable.ic_refresh));
                viewBinding.toolbar.getMenu().findItem(R.id.action_search)
                        .setIcon(AppCompatResources.getDrawable(themedContext, R.drawable.ic_search));
            }
        };
        iconTintManager.updateTint();
        viewBinding.appBar.addOnOffsetChangedListener(iconTintManager);

        nextPageLoader = new MoreContentListFooterUtil(viewBinding.moreContent.moreContentListFooter);
        nextPageLoader.setClickListener(() -> {
            if (feed != null) {
                FeedUpdateManager.getInstance().runOnce(getContext(), feed, true);
            }
        });
        viewBinding.recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView view, int deltaX, int deltaY) {
                super.onScrolled(view, deltaX, deltaY);
                updateRecyclerPadding();
            }
        });

        EventBus.getDefault().register(this);

        viewBinding.swipeRefresh.setDistanceToTriggerSync(getResources().getInteger(R.integer.swipe_refresh_distance));
        viewBinding.swipeRefresh.setOnRefreshListener(() ->
                FeedUpdateManager.getInstance().runOnceOrAsk(requireContext(), feed));

        loadItems();

        viewBinding.floatingSelectMenu.inflate(R.menu.episodes_apply_action_speeddial);
        viewBinding.floatingSelectMenu.setOnMenuItemClickListener(menuItem -> {
            if (adapter.getSelectedCount() == 0) {
                ((MainActivity) getActivity()).showSnackbarAbovePlayer(R.string.no_items_selected,
                        Snackbar.LENGTH_SHORT);
                return false;
            }
            new EpisodeMultiSelectActionHandler(((MainActivity) getActivity()), menuItem.getItemId())
                    .handleAction(adapter.getSelectedItems());
            adapter.endSelectMode();
            return true;
        });
        return viewBinding.getRoot();
    }

    private void updateRecyclerPadding() {
        boolean hasMorePages = feed != null && feed.isPaged() && feed.getNextPageLink() != null;
        boolean pageLoaderVisible = viewBinding.recyclerView.isScrolledToBottom() && hasMorePages;
        nextPageLoader.getRoot().setVisibility(pageLoaderVisible ? View.VISIBLE : View.GONE);
        int paddingBottom = 0;
        if (adapter.inActionMode()) {
            paddingBottom = (int) getResources().getDimension(R.dimen.floating_select_menu_height);
        } else if (pageLoaderVisible) {
            paddingBottom = nextPageLoader.getRoot().getMeasuredHeight();
        }
        viewBinding.recyclerView.setPadding(viewBinding.recyclerView.getPaddingLeft(), 0,
                viewBinding.recyclerView.getPaddingRight(), paddingBottom);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        EventBus.getDefault().unregister(this);
        if (disposable != null) {
            disposable.dispose();
        }
        adapter.endSelectMode();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(KEY_UP_ARROW, displayUpArrow);
        super.onSaveInstanceState(outState);
    }

    private void updateToolbar() {
        if (feed == null) {
            return;
        }
        viewBinding.toolbar.getMenu().findItem(R.id.visit_website_item).setVisible(feed.getLink() != null);
        viewBinding.toolbar.getMenu().findItem(R.id.refresh_complete_item).setVisible(feed.isPaged());
        if (StringUtils.isBlank(feed.getLink())) {
            viewBinding.toolbar.getMenu().findItem(R.id.visit_website_item).setVisible(false);
        }
        if (feed.isLocalFeed()) {
            viewBinding.toolbar.getMenu().findItem(R.id.share_item).setVisible(false);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int horizontalSpacing = (int) getResources().getDimension(R.dimen.additional_horizontal_spacing);
        viewBinding.header.headerContainer.setPadding(
                horizontalSpacing, viewBinding.header.headerContainer.getPaddingTop(),
                horizontalSpacing, viewBinding.header.headerContainer.getPaddingBottom());
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        if (feed == null) {
            ((MainActivity) getActivity()).showSnackbarAbovePlayer(
                    R.string.please_wait_for_data, Toast.LENGTH_LONG);
            return true;
        }
        if (item.getItemId() == R.id.visit_website_item) {
            IntentUtils.openInBrowser(getContext(), feed.getLink());
        } else if (item.getItemId() == R.id.share_item) {
            ShareUtils.shareFeedLink(getContext(), feed);
        } else if (item.getItemId() == R.id.refresh_item) {
            FeedUpdateManager.getInstance().runOnceOrAsk(getContext(), feed);
        } else if (item.getItemId() == R.id.refresh_complete_item) {
            new Thread(() -> {
                feed.setNextPageLink(feed.getDownloadUrl());
                feed.setPageNr(0);
                try {
                    DBWriter.resetPagedFeedPage(feed).get();
                    FeedUpdateManager.getInstance().runOnce(getContext(), feed);
                } catch (ExecutionException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }).start();
        } else if (item.getItemId() == R.id.sort_items) {
            SingleFeedSortDialog.newInstance(feed).show(getChildFragmentManager(), "SortDialog");
        } else if (item.getItemId() == R.id.rename_item) {
            new RenameFeedDialog(getActivity(), feed).show();
        } else if (item.getItemId() == R.id.remove_feed) {
            RemoveFeedDialog.show(getContext(), feed, () -> {
                ((MainActivity) getActivity()).loadFragment(UserPreferences.getDefaultPage(), null);
                // Make sure fragment is hidden before actually starting to delete
                getActivity().getSupportFragmentManager().executePendingTransactions();
            });
        } else if (item.getItemId() == R.id.action_search) {
            ((MainActivity) getActivity()).loadChildFragment(SearchFragment.newInstance(feed.getId(), feed.getTitle()));
        } else {
            return false;
        }
        return true;
    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        FeedItem selectedItem = adapter.getLongPressedItem();
        if (selectedItem == null) {
            Log.i(TAG, "Selected item at current position was null, ignoring selection");
            return super.onContextItemSelected(item);
        }
        if (adapter.onContextItemSelected(item)) {
            return true;
        }
        return FeedItemMenuHandler.onMenuItemClicked(this, item.getItemId(), selectedItem);
    }

    private void setupLoadMoreScrollListener() {
        viewBinding.recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView view, int deltaX, int deltaY) {
                super.onScrolled(view, deltaX, deltaY);
                if (!isLoadingMore && hasMoreItems && viewBinding.recyclerView.isScrolledToBottom()) {
                    /* The end of the list has been reached. Load more data. */
                    page++;
                    loadMoreItems();
                    isLoadingMore = true;
                }
            }
        });
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        MainActivity activity = (MainActivity) getActivity();
        activity.loadChildFragment(ItemPagerFragment.newInstance(feed.getItems(), feed.getItems().get(position)));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(FeedEvent event) {
        Log.d(TAG, "onEvent() called with: " + "event = [" + event + "]");
        if (event.feedId == feedID) {
            loadItems();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(FeedItemEvent event) {
        Log.d(TAG, "onEventMainThread() called with: " + "event = [" + event + "]");
        if (feed == null || feed.getItems() == null) {
            return;
        }
        for (int i = 0, size = event.items.size(); i < size; i++) {
            FeedItem item = event.items.get(i);
            int pos = FeedItemEvent.indexOfItemWithId(feed.getItems(), item.getId());
            if (pos >= 0) {
                feed.getItems().remove(pos);
                feed.getItems().add(pos, item);
                adapter.notifyItemChangedCompat(pos);
            }
        }
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void onEventMainThread(EpisodeDownloadEvent event) {
        if (feed == null) {
            return;
        }
        for (String downloadUrl : event.getUrls()) {
            int pos = EpisodeDownloadEvent.indexOfItemWithDownloadUrl(feed.getItems(), downloadUrl);
            if (pos >= 0) {
                adapter.notifyItemChangedCompat(pos);
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(PlaybackPositionEvent event) {
        for (int i = 0; i < adapter.getItemCount(); i++) {
            EpisodeItemViewHolder holder = (EpisodeItemViewHolder)
                    viewBinding.recyclerView.findViewHolderForAdapterPosition(i);
            if (holder != null && holder.isCurrentlyPlayingItem()) {
                holder.notifyPlaybackPositionUpdated(event);
                break;
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void favoritesChanged(FavoritesEvent event) {
        updateUi();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onQueueChanged(QueueEvent event) {
        updateUi();
    }

    @Override
    public void onStartSelectMode() {
        swipeActions.detach();
        viewBinding.floatingSelectMenu.getMenu().findItem(R.id.download_batch).setVisible(!feed.isLocalFeed());
        viewBinding.floatingSelectMenu.setVisibility(View.VISIBLE);
        updateRecyclerPadding();
        updateToolbar();
    }

    @Override
    public void onEndSelectMode() {
        viewBinding.floatingSelectMenu.setVisibility(View.GONE);
        updateRecyclerPadding();
        swipeActions.attachTo(viewBinding.recyclerView);
    }

    private void updateUi() {
        loadItems();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusChanged(PlayerStatusEvent event) {
        updateUi();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onUnreadItemsChanged(UnreadItemsUpdateEvent event) {
        updateUi();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFeedListChanged(FeedListUpdateEvent event) {
        if (feed != null && event.contains(feed)) {
            updateUi();
        }
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void onEventMainThread(FeedUpdateRunningEvent event) {
        nextPageLoader.setLoadingState(event.isFeedUpdateRunning);
        if (!event.isFeedUpdateRunning) {
            nextPageLoader.getRoot().setVisibility(View.GONE);
        }
        viewBinding.swipeRefresh.setRefreshing(event.isFeedUpdateRunning);
    }

    private void refreshHeaderView() {
        setupHeaderView();
        if (viewBinding == null || feed == null) {
            Log.e(TAG, "Unable to refresh header view");
            return;
        }
        loadFeedImage();
        if (feed.hasLastUpdateFailed()) {
            viewBinding.header.txtvFailure.setVisibility(View.VISIBLE);
        } else {
            viewBinding.header.txtvFailure.setVisibility(View.GONE);
        }
        if (!feed.getPreferences().getKeepUpdated()) {
            viewBinding.header.txtvUpdatesDisabled.setText(R.string.updates_disabled_label);
            viewBinding.header.txtvUpdatesDisabled.setVisibility(View.VISIBLE);
        } else {
            viewBinding.header.txtvUpdatesDisabled.setVisibility(View.GONE);
        }
        viewBinding.header.txtvTitle.setText(feed.getTitle());
        viewBinding.header.txtvAuthor.setText(feed.getAuthor());
        if (feed.getItemFilter() != null) {
            FeedItemFilter filter = feed.getItemFilter();
            if (filter.getValues().length > 0) {
                viewBinding.header.txtvInformation.setText(R.string.filtered_label);
                viewBinding.header.txtvInformation.setOnClickListener(l ->
                        FeedItemFilterDialog.newInstance(feed).show(getChildFragmentManager(), null));
                viewBinding.header.txtvInformation.setVisibility(View.VISIBLE);
            } else {
                viewBinding.header.txtvInformation.setVisibility(View.GONE);
            }
        } else {
            viewBinding.header.txtvInformation.setVisibility(View.GONE);
        }
    }

    private void setupHeaderView() {
        if (feed == null || headerCreated) {
            return;
        }

        // https://github.com/bumptech/glide/issues/529
        viewBinding.imgvBackground.setColorFilter(new LightingColorFilter(0xff666666, 0x000000));
        viewBinding.header.butShowInfo.setOnClickListener(v -> showFeedInfo());
        viewBinding.header.imgvCover.setOnClickListener(v -> showFeedInfo());
        viewBinding.header.butShowSettings.setOnClickListener(v -> {
            if (feed != null) {
                FeedSettingsFragment fragment = FeedSettingsFragment.newInstance(feed);
                ((MainActivity) getActivity()).loadChildFragment(fragment, TransitionEffect.SLIDE);
            }
        });
        viewBinding.header.butFilter.setOnClickListener(v ->
                FeedItemFilterDialog.newInstance(feed).show(getChildFragmentManager(), null));
        viewBinding.header.txtvFailure.setOnClickListener(v -> showErrorDetails());
        headerCreated = true;
    }

    private void showErrorDetails() {
        Maybe.fromCallable(
                () -> {
                    List<DownloadResult> feedDownloadLog = DBReader.getFeedDownloadLog(feedID);
                    if (feedDownloadLog.size() == 0 || feedDownloadLog.get(0).isSuccessful()) {
                        return null;
                    }
                    return feedDownloadLog.get(0);
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    downloadStatus -> new DownloadLogDetailsDialog(getContext(), downloadStatus).show(),
                    error -> error.printStackTrace(),
                    () -> new DownloadLogFragment().show(getChildFragmentManager(), null));
    }

    private void showFeedInfo() {
        if (feed != null) {
            FeedInfoFragment fragment = FeedInfoFragment.newInstance(feed);
            ((MainActivity) getActivity()).loadChildFragment(fragment, TransitionEffect.SLIDE);
        }
    }

    private void loadFeedImage() {
        Glide.with(this)
                .load(feed.getImageUrl())
                .apply(new RequestOptions()
                    .placeholder(R.color.image_readability_tint)
                    .error(R.color.image_readability_tint)
                    .transform(new FastBlurTransformation())
                    .dontAnimate())
                .into(viewBinding.imgvBackground);

        Glide.with(this)
                .load(feed.getImageUrl())
                .apply(new RequestOptions()
                    .placeholder(R.color.light_gray)
                    .error(R.color.light_gray)
                    .fitCenter()
                    .dontAnimate())
                .into(viewBinding.header.imgvCover);
    }

    private void loadItems() {
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Observable.fromCallable(
                () -> {
                    feed = DBReader.getFeed(feedID, true, 0, page * EPISODES_PER_PAGE);
                    int count = DBReader.getFeedEpisodeCount(feed.getId(), feed.getItemFilter());
                    return new Pair<>(feed, count);
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    result -> {
                        hasMoreItems = !(page == 1 && feed.getItems().size() < EPISODES_PER_PAGE);
                        swipeActions.setFilter(feed.getItemFilter());
                        refreshHeaderView();
                        viewBinding.progressBar.setVisibility(View.GONE);
                        adapter.setDummyViews(0);
                        adapter.updateItems(feed.getItems());
                        adapter.setTotalNumberOfItems(result.second);
                        updateToolbar();
                    }, error -> {
                        feed = null;
                        refreshHeaderView();
                        adapter.setDummyViews(0);
                        adapter.updateItems(Collections.emptyList());
                        updateToolbar();
                        Log.e(TAG, Log.getStackTraceString(error));
                    });
    }

    private void loadMoreItems() {
        if (disposable != null) {
            disposable.dispose();
        }
        isLoadingMore = true;
        adapter.setDummyViews(1);
        adapter.notifyItemInserted(adapter.getItemCount() - 1);
        disposable = Observable.fromCallable(() -> DBReader.getFeed(feedID, true,
                        (page - 1) * EPISODES_PER_PAGE, EPISODES_PER_PAGE))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        data -> {
                            if (data.getItems().size() < EPISODES_PER_PAGE) {
                                hasMoreItems = false;
                            }
                            feed.getItems().addAll(data.getItems());
                            adapter.setDummyViews(0);
                            adapter.updateItems(feed.getItems());
                            if (adapter.shouldSelectLazyLoadedItems()) {
                                adapter.setSelected(feed.getItems().size() - data.getItems().size(),
                                        feed.getItems().size(), true);
                            }
                        }, error -> {
                            adapter.setDummyViews(0);
                            adapter.updateItems(Collections.emptyList());
                            Log.e(TAG, Log.getStackTraceString(error));
                        }, () -> {
                            // Make sure to not always load 2 pages at once
                            viewBinding.recyclerView.post(() -> isLoadingMore = false);
                        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onKeyUp(KeyEvent event) {
        if (!isAdded() || !isVisible() || !isMenuVisible()) {
            return;
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_T:
                viewBinding.recyclerView.smoothScrollToPosition(0);
                break;
            case KeyEvent.KEYCODE_B:
                viewBinding.recyclerView.smoothScrollToPosition(adapter.getItemCount() - 1);
                break;
            default:
                break;
        }
    }

    private class FeedItemListAdapter extends EpisodeItemListAdapter {
        public FeedItemListAdapter(MainActivity mainActivity) {
            super(mainActivity);
        }

        @Override
        protected void beforeBindViewHolder(EpisodeItemViewHolder holder, int pos) {
            holder.coverHolder.setVisibility(View.GONE); // Load it ourselves
        }

        @Override
        protected void afterBindViewHolder(EpisodeItemViewHolder holder, int pos) {
            holder.coverHolder.setVisibility(View.VISIBLE);
            new CoverLoader()
                    .withUri(holder.getFeedItem().getImageLocation()) // Ignore "Show episode cover" setting
                    .withFallbackUri(holder.getFeedItem().getFeed().getImageUrl())
                    .withPlaceholderView(holder.placeholder)
                    .withCoverView(holder.cover)
                    .load();
        }

        @Override
        public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
            super.onCreateContextMenu(menu, v, menuInfo);
            if (!inActionMode()) {
                menu.findItem(R.id.multi_select).setVisible(true);
            }
            MenuItemUtils.setOnClickListeners(menu, FeedItemlistFragment.this::onContextItemSelected);
        }
    }
}
