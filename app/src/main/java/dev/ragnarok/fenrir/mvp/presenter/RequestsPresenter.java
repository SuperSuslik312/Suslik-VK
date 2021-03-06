package dev.ragnarok.fenrir.mvp.presenter;

import static dev.ragnarok.fenrir.util.Utils.getCauseIfRuntime;
import static dev.ragnarok.fenrir.util.Utils.nonEmpty;
import static dev.ragnarok.fenrir.util.Utils.trimmedIsEmpty;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import dev.ragnarok.fenrir.R;
import dev.ragnarok.fenrir.domain.IRelationshipInteractor;
import dev.ragnarok.fenrir.domain.InteractorFactory;
import dev.ragnarok.fenrir.model.Owner;
import dev.ragnarok.fenrir.model.User;
import dev.ragnarok.fenrir.model.UsersPart;
import dev.ragnarok.fenrir.mvp.presenter.base.AccountDependencyPresenter;
import dev.ragnarok.fenrir.mvp.reflect.OnGuiCreated;
import dev.ragnarok.fenrir.mvp.view.IRequestsView;
import dev.ragnarok.fenrir.settings.Settings;
import dev.ragnarok.fenrir.util.Objects;
import dev.ragnarok.fenrir.util.Pair;
import dev.ragnarok.fenrir.util.RxUtils;
import dev.ragnarok.fenrir.util.Utils;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;


public class RequestsPresenter extends AccountDependencyPresenter<IRequestsView> {

    private static final int ALL = 0;
    private static final int SEACRH_CACHE = 1;
    private static final int SEARCH_WEB = 2;

    private static final int WEB_SEARCH_DELAY = 1000;
    private static final int WEB_SEARCH_COUNT_PER_LOAD = 100;

    private final IRelationshipInteractor relationshipInteractor;
    private final int userId;

    private final ArrayList<UsersPart> data;
    private final CompositeDisposable actualDataDisposable = new CompositeDisposable();
    private final CompositeDisposable cacheDisposable = new CompositeDisposable();
    private final CompositeDisposable seacrhDisposable = new CompositeDisposable();
    private final boolean isNotFriendShow;
    private String q;
    private boolean actualDataReceived;
    private boolean actualDataEndOfContent;
    private boolean actualDataLoadingNow;
    private boolean cacheLoadingNow;
    private boolean searchRunNow;
    private boolean doLoadTabs;

    public RequestsPresenter(int accountId, int userId, @Nullable Bundle savedInstanceState) {
        super(accountId, savedInstanceState);
        this.userId = userId;
        relationshipInteractor = InteractorFactory.createRelationshipInteractor();

        data = new ArrayList<>(3);
        data.add(ALL, new UsersPart(R.string.all_friends, new ArrayList<>(), true));
        data.add(SEACRH_CACHE, new UsersPart(R.string.results_in_the_cache, new ArrayList<>(), false));
        data.add(SEARCH_WEB, new UsersPart(R.string.results_in_a_network, new ArrayList<>(), false));
        isNotFriendShow = Settings.get().other().isNot_friend_show();
    }

    private static boolean allow(User user, String preparedQ) {
        String full = user.getFullName().toLowerCase();
        return full.contains(preparedQ);
    }

    private void requestActualData(int offset, boolean do_scan) {
        int accountId = getAccountId();
        if (accountId != userId) {
            cacheLoadingNow = false;
            resolveRefreshingView();
            return;
        }
        actualDataLoadingNow = true;
        resolveRefreshingView();

        actualDataDisposable.add(relationshipInteractor.getRequests(accountId, offset, isNotFriendShow ? 1000 : 200)
                .compose(RxUtils.applySingleIOToMainSchedulers())
                .subscribe(users -> onActualDataReceived(offset, users, do_scan), this::onActualDataGetError));
    }

    private void onActualDataGetError(Throwable t) {
        actualDataLoadingNow = false;
        resolveRefreshingView();
        callView(v -> showError(v, getCauseIfRuntime(t)));
    }

    @Override
    public void onGuiCreated(@NonNull IRequestsView view) {
        super.onGuiCreated(view);
        view.displayData(data, isSeacrhNow());
    }

    private void resolveRefreshingView() {
        callResumedView(v -> v.showRefreshing(!isSeacrhNow() && actualDataLoadingNow));
    }

    @Override
    public void onGuiResumed() {
        super.onGuiResumed();
        resolveRefreshingView();
        if (doLoadTabs) {
            return;
        } else {
            doLoadTabs = true;
        }
        loadAllCachedData();
        if (!isNotFriendShow) {
            requestActualData(0, false);
        }
    }

    private void onActualDataReceived(int offset, List<User> users, boolean do_scan) {
        if (do_scan && isNotFriendShow) {
            List<Owner> not_friends = new ArrayList<>();
            for (User i : getAllData()) {
                if (Utils.indexOf(users, i.getId()) == -1) {
                    not_friends.add(i);
                }
            }
            if (not_friends.size() > 0) {
                callView(view -> view.showNotRequests(not_friends, getAccountId()));
            }
        }
        // reset cache loading
        cacheDisposable.clear();
        cacheLoadingNow = false;

        actualDataEndOfContent = users.isEmpty();
        actualDataReceived = true;
        actualDataLoadingNow = false;

        if (offset > 0) {
            int startSize = getAllData().size();
            getAllData().addAll(users);

            if (!isSeacrhNow()) {
                callView(view -> view.notifyItemRangeInserted(startSize, users.size()));
            }
        } else {
            getAllData().clear();
            getAllData().addAll(users);

            if (!isSeacrhNow()) {
                safelyNotifyDataSetChanged();
            }
        }

        resolveRefreshingView();
    }

    private void loadAllCachedData() {
        int accountId = getAccountId();

        cacheLoadingNow = true;
        cacheDisposable.add(relationshipInteractor.getCachedRequests(accountId)
                .compose(RxUtils.applySingleIOToMainSchedulers())
                .subscribe(this::onCachedDataReceived, this::onCacheGetError));
    }

    private void onCacheGetError(Throwable t) {
        cacheLoadingNow = false;
        callView(v -> showError(v, t));
        if (isNotFriendShow) {
            requestActualData(0, false);
        }
    }

    private void onCachedDataReceived(List<User> users) {
        cacheLoadingNow = false;

        getAllData().clear();
        getAllData().addAll(users);

        safelyNotifyDataSetChanged();
        if (isNotFriendShow) {
            requestActualData(0, users.size() > 0);
        }
    }

    private void safelyNotifyDataSetChanged() {
        callView(v -> v.notifyDatasetChanged(isSeacrhNow()));
    }

    private List<User> getAllData() {
        return data.get(ALL).users;
    }

    public void fireRefresh() {
        if (!isSeacrhNow()) {
            cacheDisposable.clear();
            actualDataDisposable.clear();
            cacheLoadingNow = false;
            actualDataLoadingNow = false;

            requestActualData(0, false);
        }
    }

    private void onSearchQueryChanged(boolean seacrhStateChanged) {
        seacrhDisposable.clear();

        if (seacrhStateChanged) {
            resolveSwipeRefreshAvailability();
        }

        if (!isSeacrhNow()) {
            data.get(ALL).enable = true;

            data.get(SEARCH_WEB).users.clear();
            data.get(SEARCH_WEB).enable = false;
            data.get(SEARCH_WEB).displayCount = null;

            data.get(SEACRH_CACHE).users.clear();
            data.get(SEACRH_CACHE).enable = false;

            callView(view -> view.notifyDatasetChanged(false));
            return;
        }

        data.get(ALL).enable = false;

        reFillCache();
        data.get(SEACRH_CACHE).enable = true;

        data.get(SEARCH_WEB).users.clear();
        data.get(SEARCH_WEB).enable = true;
        data.get(SEARCH_WEB).displayCount = null;

        callView(view -> view.notifyDatasetChanged(true));

        runNetSeacrh(0, true);
    }

    private void runNetSeacrh(int offset, boolean withDelay) {
        if (trimmedIsEmpty(q)) {
            return;
        }

        seacrhDisposable.clear();
        searchRunNow = true;

        String query = q;
        int accountId = getAccountId();

        Single<Pair<List<User>, Integer>> single;
        Single<Pair<List<User>, Integer>> netSingle = relationshipInteractor.seacrhFriends(accountId, userId, WEB_SEARCH_COUNT_PER_LOAD, offset, query);

        if (withDelay) {
            single = Single.just(new Object())
                    .delay(WEB_SEARCH_DELAY, TimeUnit.MILLISECONDS)
                    .flatMap(ignored -> netSingle);
        } else {
            single = netSingle;
        }

        seacrhDisposable.add(single
                .compose(RxUtils.applySingleIOToMainSchedulers())
                .subscribe(pair -> onSearchDataReceived(offset, pair.getFirst(), pair.getSecond()), this::onSearchError));
    }

    private void onSearchError(Throwable t) {
        searchRunNow = false;
        callView(v -> showError(v, getCauseIfRuntime(t)));
    }

    private void onSearchDataReceived(int offset, List<User> users, int fullCount) {
        searchRunNow = false;

        List<User> searchData = data.get(SEARCH_WEB).users;

        data.get(SEARCH_WEB).displayCount = fullCount;

        if (offset == 0) {
            searchData.clear();
            searchData.addAll(users);
            callView(view -> view.notifyDatasetChanged(isSeacrhNow()));
        } else {
            int sizeBefore = searchData.size();
            int currentCacheSize = data.get(SEACRH_CACHE).users.size();
            searchData.addAll(users);
            callView(view -> view.notifyItemRangeInserted(sizeBefore + currentCacheSize, users.size()));
        }
    }

    private void reFillCache() {
        data.get(SEACRH_CACHE).users.clear();

        List<User> db = data.get(ALL).users;

        String preparedQ = q.toLowerCase().trim();

        int count = 0;
        for (User user : db) {
            if (allow(user, preparedQ)) {
                data.get(SEACRH_CACHE).users.add(user);
                count++;
            }
        }

        data.get(SEACRH_CACHE).displayCount = count;
    }

    private boolean isSeacrhNow() {
        return nonEmpty(q);
    }

    @OnGuiCreated
    private void resolveSwipeRefreshAvailability() {
        callView(v -> v.setSwipeRefreshEnabled(!isSeacrhNow()));
    }

    public void fireSearchRequestChanged(String q) {
        String query = q == null ? null : q.trim();

        if (Objects.safeEquals(query, this.q)) {
            return;
        }

        boolean wasSearch = isSeacrhNow();
        this.q = query;

        onSearchQueryChanged(wasSearch != isSeacrhNow());
    }

    @Override
    public void onDestroyed() {
        seacrhDisposable.dispose();
        cacheDisposable.dispose();
        actualDataDisposable.dispose();
        super.onDestroyed();
    }

    private void loadMore() {
        if (isSeacrhNow()) {
            if (searchRunNow) {
                return;
            }

            runNetSeacrh(data.get(SEARCH_WEB).users.size(), false);
        } else {
            if (actualDataLoadingNow || cacheLoadingNow || !actualDataReceived || actualDataEndOfContent) {
                return;
            }

            requestActualData(getAllData().size(), false);
        }
    }

    public void fireScrollToEnd() {
        loadMore();
    }

    public void fireUserClick(User user) {
        callView(v -> v.showUserWall(getAccountId(), user));
    }
}
