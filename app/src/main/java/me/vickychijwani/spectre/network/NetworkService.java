package me.vickychijwani.spectre.network;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.crashlytics.android.Crashlytics;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.squareup.otto.Bus;
import com.squareup.otto.Subscribe;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import io.realm.Case;
import io.realm.Realm;
import io.realm.RealmModel;
import io.realm.RealmObject;
import io.realm.RealmQuery;
import io.realm.RealmResults;
import io.realm.Sort;
import me.vickychijwani.spectre.SpectreApplication;
import me.vickychijwani.spectre.analytics.AnalyticsService;
import me.vickychijwani.spectre.error.ExpiredTokenUsedException;
import me.vickychijwani.spectre.error.PostConflictFoundException;
import me.vickychijwani.spectre.error.TokenRevocationFailedException;
import me.vickychijwani.spectre.event.ApiCallEvent;
import me.vickychijwani.spectre.event.ApiErrorEvent;
import me.vickychijwani.spectre.event.BlogSettingsLoadedEvent;
import me.vickychijwani.spectre.event.BusProvider;
import me.vickychijwani.spectre.event.ConfigurationLoadedEvent;
import me.vickychijwani.spectre.event.CreatePostEvent;
import me.vickychijwani.spectre.event.DataRefreshedEvent;
import me.vickychijwani.spectre.event.DeletePostEvent;
import me.vickychijwani.spectre.event.FileUploadErrorEvent;
import me.vickychijwani.spectre.event.FileUploadEvent;
import me.vickychijwani.spectre.event.FileUploadedEvent;
import me.vickychijwani.spectre.event.ForceCancelRefreshEvent;
import me.vickychijwani.spectre.event.GhostVersionLoadedEvent;
import me.vickychijwani.spectre.event.LoadBlogSettingsEvent;
import me.vickychijwani.spectre.event.LoadConfigurationEvent;
import me.vickychijwani.spectre.event.LoadGhostVersionEvent;
import me.vickychijwani.spectre.event.LoadPostsEvent;
import me.vickychijwani.spectre.event.LoadTagsEvent;
import me.vickychijwani.spectre.event.LoadUserEvent;
import me.vickychijwani.spectre.event.LoginDoneEvent;
import me.vickychijwani.spectre.event.LoginErrorEvent;
import me.vickychijwani.spectre.event.LoginStartEvent;
import me.vickychijwani.spectre.event.LogoutEvent;
import me.vickychijwani.spectre.event.LogoutStatusEvent;
import me.vickychijwani.spectre.event.PasswordChangedEvent;
import me.vickychijwani.spectre.event.PostConflictFoundEvent;
import me.vickychijwani.spectre.event.PostCreatedEvent;
import me.vickychijwani.spectre.event.PostDeletedEvent;
import me.vickychijwani.spectre.event.PostReplacedEvent;
import me.vickychijwani.spectre.event.PostSavedEvent;
import me.vickychijwani.spectre.event.PostSyncedEvent;
import me.vickychijwani.spectre.event.PostsLoadedEvent;
import me.vickychijwani.spectre.event.RefreshDataEvent;
import me.vickychijwani.spectre.event.SavePostEvent;
import me.vickychijwani.spectre.event.SyncPostsEvent;
import me.vickychijwani.spectre.event.TagsLoadedEvent;
import me.vickychijwani.spectre.event.UserLoadedEvent;
import me.vickychijwani.spectre.model.entity.AuthToken;
import me.vickychijwani.spectre.model.entity.ConfigurationParam;
import me.vickychijwani.spectre.model.entity.ETag;
import me.vickychijwani.spectre.model.entity.PendingAction;
import me.vickychijwani.spectre.model.entity.Post;
import me.vickychijwani.spectre.model.entity.Setting;
import me.vickychijwani.spectre.model.entity.Tag;
import me.vickychijwani.spectre.model.entity.User;
import me.vickychijwani.spectre.network.entity.AuthReqBody;
import me.vickychijwani.spectre.network.entity.ConfigurationList;
import me.vickychijwani.spectre.network.entity.PostList;
import me.vickychijwani.spectre.network.entity.PostStubList;
import me.vickychijwani.spectre.network.entity.RefreshReqBody;
import me.vickychijwani.spectre.network.entity.RevokeReqBody;
import me.vickychijwani.spectre.network.entity.SettingsList;
import me.vickychijwani.spectre.network.entity.UserList;
import me.vickychijwani.spectre.pref.AppState;
import me.vickychijwani.spectre.pref.UserPrefs;
import me.vickychijwani.spectre.util.DateTimeUtils;
import me.vickychijwani.spectre.util.NetworkUtils;
import me.vickychijwani.spectre.util.PostUtils;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Action2;

public class NetworkService {

    private static final String TAG = "NetworkService";

    // number of posts to fetch
    private static final int POSTS_FETCH_LIMIT = 30;

    private Realm mRealm = null;
    private GhostApiService mApi = null;
    private AuthToken mAuthToken = null;
    private OkHttpClient mOkHttpClient = null;
    private final JSONObjectConverterFactory mJSONObjectConverterFactory;
    private final GsonConverterFactory mGsonConverterFactory;

    private boolean mbAuthRequestOnGoing = false;
    private RetrofitError mRefreshError = null;
    private final ArrayDeque<ApiCallEvent> mApiEventQueue = new ArrayDeque<>();
    private final ArrayDeque<ApiCallEvent> mRefreshEventsQueue = new ArrayDeque<>();

    public NetworkService() {
        Crashlytics.log(Log.DEBUG, TAG, "Initializing NetworkService...");
        mGsonConverterFactory = GsonConverterFactory.create(GhostApiUtils.getGson());
        mJSONObjectConverterFactory = JSONObjectConverterFactory.create();
    }

    public void start(Context context, OkHttpClient okHttpClient) {
        mOkHttpClient = okHttpClient;
        getBus().register(this);
        mRealm = Realm.getDefaultInstance();
        if (AppState.getInstance(context).getBoolean(AppState.Key.LOGGED_IN)) {
            mAuthToken = mRealm.where(AuthToken.class).findFirst();
            String blogUrl = UserPrefs.getInstance(context).getString(UserPrefs.Key.BLOG_URL);
            mApi = buildApiService(blogUrl, true);
        }
    }

    // I don't know how to call this from the Application class!
    @SuppressWarnings("unused")
    public void stop() {
        getBus().unregister(this);
        mRealm.close();
    }

    @Subscribe
    public void onLoginStartEvent(final LoginStartEvent event) {
        if (mbAuthRequestOnGoing) return;
        mbAuthRequestOnGoing = true;
        mApi = buildApiService(event.blogUrl, true);
        GhostApiService apiServiceForClientSecret = buildApiService(event.blogUrl, false);
        GhostApiUtils.doWithClientSecret(apiServiceForClientSecret, clientSecret -> {
            doLogin(event, clientSecret);
        });
    }

    private void doLogin(@NonNull LoginStartEvent event, @Nullable String clientSecret) {
        AuthReqBody credentials = new AuthReqBody(event.username, event.password, clientSecret);
        mApi.getAuthToken(credentials).enqueue(new Callback<AuthToken>() {
            @Override
            public void onResponse(Call<AuthToken> call, Response<AuthToken> response) {
                AuthToken authToken = response.body();
                onNewAuthToken(authToken);
                getBus().post(new LoginDoneEvent(event.blogUrl, event.username, event.password,
                                                 event.initiatedByUser));
            }

            @Override
            public void onFailure(Call<AuthToken> call, Throwable t) {
                mbAuthRequestOnGoing = false;
                // if this request was not initiated by the user and the response is 401 Unauthorized,
                // it means the password changed - ask for the password again
                if (!event.initiatedByUser && NetworkUtils.isUnauthorized(error)) {
                    clearSavedPassword();
                    getBus().post(new PasswordChangedEvent());
                } else {
                    getBus().post(new LoginErrorEvent(error, event.blogUrl, event.initiatedByUser));
                }
                flushApiEventQueue(true);
            }
        });
    }

    @Subscribe
    public void onRefreshDataEvent(RefreshDataEvent event) {
        // do nothing if a refresh is already in progress
        // optimization disabled because sometimes (rarely) the queue doesn't get emptied correctly
        // e.g., logout followed by login
//        if (! mRefreshEventsQueue.isEmpty()) {
//            refreshSucceeded(null);
//            return;
//        }

        mRefreshEventsQueue.clear();
        mRefreshError = null;           // clear last error if any
        mRefreshEventsQueue.addAll(Arrays.asList(
                new LoadUserEvent(true),
                new LoadBlogSettingsEvent(true),
                new LoadConfigurationEvent(true),
                new SyncPostsEvent(true)
        ));

        for (ApiCallEvent refreshEvent : mRefreshEventsQueue) {
            if (event.loadCachedData) {
                refreshEvent.loadCachedData();
            }
            getBus().post(refreshEvent);
        }
    }

    @Subscribe
    public void onForceCancelRefreshEvent(ForceCancelRefreshEvent event) {
        // sometimes (rarely) the DataRefreshedEvent is not sent because an ApiCallEvent
        // doesn't get cleared from the queue, this is to guard against that
        if (! mRefreshEventsQueue.isEmpty()) {
            mRefreshEventsQueue.clear();
            mRefreshError = null;           // clear last error if any
        }
    }

    @Subscribe
    public void onLoadGhostVersionEvent(LoadGhostVersionEvent event) {
        if (mAuthToken == null) {
            return; // can't do much, not logged in
        }

        final String UNKNOWN_VERSION = "Unknown";

        if (! event.forceNetworkCall) {
            RealmQuery<ConfigurationParam> query = mRealm.where(ConfigurationParam.class)
                    .equalTo("key", "version", Case.INSENSITIVE);
            if (query.count() > 0) {
                getBus().post(new GhostVersionLoadedEvent(query.findFirst().getValue()));
                return;
            }
        }

        if (! validateAccessToken(event)) return;

        Action0 checkVersionInConfiguration = () -> {
            Action1<List<ConfigurationParam>> successCallback = configParams -> {
                boolean versionFound = false;
                for (ConfigurationParam param : configParams) {
                    if ("version".equals(param.getKey())) {
                        versionFound = true;
                        getBus().post(new GhostVersionLoadedEvent(param.getValue()));
                    }
                }
                if (! versionFound) {
                    getBus().post(new GhostVersionLoadedEvent(UNKNOWN_VERSION));
                }
            };
            Action1<RetrofitError> failureCallback = error -> {
                if (NetworkUtils.isRealError(error)) {
                    getBus().post(new GhostVersionLoadedEvent(UNKNOWN_VERSION));
                } else {
                    // fallback to cached data
                    successCallback.call(mRealm.where(ConfigurationParam.class).findAll());
                }
            };
            doLoadConfiguration(new LoadConfigurationEvent(true), successCallback, failureCallback);
        };

        mApi.getVersion(mAuthToken.getAuthHeader()).enqueue(new Callback<JSONObject>() {
            @Override
            public void onResponse(Call<JSONObject> call, Response<JSONObject> response) {
                if (response.isSuccessful()) {
                    JSONObject jsonObject = response.body();
                    try {
                        String ghostVersion = jsonObject
                                .getJSONArray("configuration")
                                .getJSONObject(0)
                                .getString("version");
                        getBus().post(new GhostVersionLoadedEvent(ghostVersion));
                    } catch (JSONException e) {
                        getBus().post(new GhostVersionLoadedEvent(UNKNOWN_VERSION));
                    }
                } else {
                    if (response.code() == HttpURLConnection.HTTP_NOT_FOUND) {
                        // this condition means the version is < 0.7.9, because that is when
                        // the new /configuration/about endpoint was introduced
                        // FIXME remove this mess once we stop supporting < 0.7.9
                        checkVersionInConfiguration.call();
                    } else {
                        getBus().post(new GhostVersionLoadedEvent(UNKNOWN_VERSION));
                    }
                }
            }

            @Override
            public void onFailure(Call<JSONObject> call, Throwable error) {
            }
        });
    }

    @Subscribe
    public void onLoadUserEvent(final LoadUserEvent event) {
        if (event.loadCachedData || ! event.forceNetworkCall) {
            RealmResults<User> users = mRealm.where(User.class).findAll();
            if (users.size() > 0) {
                getBus().post(new UserLoadedEvent(users.first()));
                refreshSucceeded(event);
                return;
            }
            // else no users found in db, force a network call!
        }

        CHECK IF OKHTTP USES E-TAGS AUTOMATICALLY
        if (! validateAccessToken(event)) return;
        mApi.getCurrentUser(mAuthToken.getAuthHeader(), loadEtag(ETag.TYPE_CURRENT_USER)).enqueue(new Callback<UserList>() {
            @Override
            public void onResponse(Call<UserList> call, Response<UserList> response) {
                if (response.isSuccessful()) {
                    UserList userList = response.body();
                    storeEtag(response.headers(), ETag.TYPE_CURRENT_USER);
                    createOrUpdateModel(userList.users);
                    getBus().post(new UserLoadedEvent(userList.users.get(0)));

                    // download all posts again to enforce role-based permissions for this user
                    removeEtag(ETag.TYPE_ALL_POSTS);
                    getBus().post(new SyncPostsEvent(false));

                    refreshSucceeded(event);
                } else {
                    // fallback to cached data
                    RealmResults<User> users = mRealm.where(User.class).findAll();
                    if (users.size() > 0) {
                        getBus().post(new UserLoadedEvent(users.first()));
                    }

                    if (response.code() == HttpURLConnection.HTTP_NOT_MODIFIED) {
                        refreshSucceeded(event);
                    } else if (NetworkUtils.isUnauthorized(response)) {
                        // defer the event and try to re-authorize
                        refreshAccessToken(event);
                    } else {
                        getBus().post(new ApiErrorEvent(error));
                        refreshFailed(event, error);
                    }
                }
            }

            @Override
            public void onFailure(Call<UserList> call, Throwable error) {
                // error in transport layer, or lower
                getBus().post(new ApiErrorEvent(error));
                refreshFailed(event, error);
            }
        });
    }

    @Subscribe
    public void onLoadBlogSettingsEvent(final LoadBlogSettingsEvent event) {
        if (event.loadCachedData || ! event.forceNetworkCall) {
            RealmResults<Setting> settings = mRealm.where(Setting.class).findAll();
            if (settings.size() > 0) {
                getBus().post(new BlogSettingsLoadedEvent(settings));
                refreshSucceeded(event);
                return;
            }
            // no settings found in db, force a network call!
        }

        if (! validateAccessToken(event)) return;
        mApi.getSettings(mAuthToken.getAuthHeader(), loadEtag(ETag.TYPE_BLOG_SETTINGS)).enqueue(new Callback<SettingsList>() {
            @Override
            public void onResponse(Call<SettingsList> call, Response<SettingsList> response) {
                if (response.isSuccessful()) {
                    SettingsList settingsList = response.body();
                    storeEtag(response.headers(), ETag.TYPE_BLOG_SETTINGS);
                    createOrUpdateModel(settingsList.settings);
                    savePermalinkFormat(settingsList.settings);
                    getBus().post(new BlogSettingsLoadedEvent(settingsList.settings));
                    refreshSucceeded(event);
                } else {
                    // fallback to cached data
                    RealmResults<Setting> settings = mRealm.where(Setting.class).findAll();
                    if (settings.size() > 0) {
                        getBus().post(new BlogSettingsLoadedEvent(settings));
                    }

                    if (response.code() == HttpURLConnection.HTTP_NOT_MODIFIED) {
                        refreshSucceeded(event);
                    } else if (NetworkUtils.isUnauthorized(response)) {
                        // defer the event and try to re-authorize
                        refreshAccessToken(event);
                    } else {
                        getBus().post(new ApiErrorEvent(error));
                        refreshFailed(event, error);
                    }
                }
            }

            @Override
            public void onFailure(Call<SettingsList> call, Throwable error) {
                // error in transport layer, or lower
                getBus().post(new ApiErrorEvent(error));
                refreshFailed(event, error);
            }
        });
    }

    @Subscribe
    public void onLoadConfigurationEvent(final LoadConfigurationEvent event) {
        Action1<List<ConfigurationParam>> successCallback = configParams -> {
            getBus().post(new ConfigurationLoadedEvent(configParams));
            refreshSucceeded(event);
        };
        Action1<RetrofitError> failureCallback = error -> {
            // fallback to cached data
            RealmResults<ConfigurationParam> params = mRealm.where(ConfigurationParam.class).findAll();
            if (params.size() > 0) {
                getBus().post(new ConfigurationLoadedEvent(params));
            }
            if (!NetworkUtils.isUnauthorized(error) && NetworkUtils.isRealError(error)) {
                refreshFailed(event, error);
            } else {
                refreshSucceeded(event);
            }
        };
        doLoadConfiguration(event, successCallback, failureCallback);
    }

    private void doLoadConfiguration(final LoadConfigurationEvent event,
                                    @NonNull Action1<List<ConfigurationParam>> successCallback,
                                    @NonNull Action1<RetrofitError> failureCallback) {
        if (event.loadCachedData || ! event.forceNetworkCall) {
            RealmResults<ConfigurationParam> params = mRealm.where(ConfigurationParam.class).findAll();
            if (params.size() > 0) {
                successCallback.call(params);
                return;
            }
            // no configuration params found in db, force a network call!
        }

        if (! validateAccessToken(event)) return;
        mApi.getConfiguration(mAuthToken.getAuthHeader(), loadEtag(ETag.TYPE_CONFIGURATION), new Callback<ConfigurationList>() {
            @Override
            public void success(ConfigurationList configurationList, Response response) {
                storeEtag(response.getHeaders(), ETag.TYPE_CONFIGURATION);
                createOrUpdateModel(configurationList.configuration);
                successCallback.call(configurationList.configuration);
            }

            @Override
            public void failure(RetrofitError error) {
                if (NetworkUtils.isUnauthorized(error)) {
                    // defer the event and try to re-authorize
                    refreshAccessToken(event);
                } else if (NetworkUtils.isRealError(error)) {
                    getBus().post(new ApiErrorEvent(error));
                }
                failureCallback.call(error);
            }
        });
    }

    @Subscribe
    public void onLoadPostsEvent(final LoadPostsEvent event) {
        if (event.loadCachedData || ! event.forceNetworkCall) {
            List<Post> posts = getPostsSorted();
            // if there are no posts, there could be 2 cases:
            // 1. there are actually no posts
            // 2. we just haven't fetched any posts from the server yet (Realm returns an empty list in this case too)
            if (posts.size() > 0) {
                getBus().post(new PostsLoadedEvent(posts, POSTS_FETCH_LIMIT));
                refreshSucceeded(event);
                return;
            }
        }

        if (! validateAccessToken(event)) return;
        mApi.getPosts(mAuthToken.getAuthHeader(), loadEtag(ETag.TYPE_ALL_POSTS), POSTS_FETCH_LIMIT).enqueue(new Callback<PostList>() {
            @Override
            public void onResponse(Call<PostList> call, Response<PostList> response) {
                if (response.isSuccessful()) {
                    PostList postList = response.body();
                    storeEtag(response.headers(), ETag.TYPE_ALL_POSTS);

                    // if this user is only an author, filter out posts they're not authorized to access
                    // FIXME if the last POSTS_FETCH_LIMIT number of posts are not owned by this author,
                    // FIXME we'll end up with no posts displayed in the UI!
                    RealmResults<User> users = mRealm.where(User.class).findAll();
                    if (users.size() > 0) {
                        User user = users.first();
                        if (user.hasOnlyAuthorRole()) {
                            int currentUser = user.getId();
                            // reverse iteration because in forward iteration, indices change on deleting
                            for (int i = postList.posts.size() - 1; i >= 0; --i) {
                                Post post = postList.posts.get(i);
                                if (post.getAuthor() != currentUser) {
                                    postList.posts.remove(i);
                                }
                            }
                        }
                    }

                    // delete posts that are no longer present on the server
                    // this assumes that postList.posts is a list of ALL posts on the server
                    // FIXME time complexity is quadratic in the number of posts!
                    Observable.from(mRealm.where(Post.class).findAll())
                            .filter(cached -> postList.indexOf(cached.getUuid()) == -1)
                            .toList()
                            .forEach(NetworkService.this::deleteModels);

                    // skip edited posts because they've not yet been uploaded
                    RealmResults<Post> localOnlyEdits = mRealm.where(Post.class)
                            .equalTo("pendingActions.type", PendingAction.EDIT_LOCAL)
                            .or().equalTo("pendingActions.type", PendingAction.EDIT)
                            .findAll();
                    for (int i = postList.posts.size() - 1; i >= 0; --i) {
                        for (int j = 0; j < localOnlyEdits.size(); ++j) {
                            if (postList.posts.get(i).getUuid().equals(localOnlyEdits.get(j).getUuid())) {
                                postList.posts.remove(i);
                            }
                        }
                    }

                    // make sure drafts have a publishedAt of FAR_FUTURE so they're sorted to the top
                    Observable.from(postList.posts)
                            .filter(post -> post.getPublishedAt() == null)
                            .forEach(post -> post.setPublishedAt(DateTimeUtils.FAR_FUTURE));

                    // now create / update received posts
                    // TODO use Realm#insertOrUpdate() for faster insertion here: https://realm.io/news/realm-java-1.1.0/
                    createOrUpdateModel(postList.posts);
                    getBus().post(new PostsLoadedEvent(getPostsSorted(), POSTS_FETCH_LIMIT));

                    refreshSucceeded(event);
                } else {
                    // fallback to cached data
                    getBus().post(new PostsLoadedEvent(getPostsSorted(), POSTS_FETCH_LIMIT));
                    if (response.code() == HttpURLConnection.HTTP_NOT_MODIFIED) {
                        refreshSucceeded(event);
                    } else if (NetworkUtils.isUnauthorized(response)) {
                        // defer the event and try to re-authorize
                        refreshAccessToken(event);
                    } else {
                        getBus().post(new ApiErrorEvent(error));
                        refreshFailed(event, error);
                    }
                }
            }

            @Override
            public void onFailure(Call<PostList> call, Throwable error) {
                // error in transport layer, or lower
                getBus().post(new ApiErrorEvent(error));
                refreshFailed(event, error);
            }
        });
    }

    @Subscribe
    public void onCreatePostEvent(final CreatePostEvent event) {
        Crashlytics.log(Log.DEBUG, TAG, "[onCreatePostEvent] creating new post");
        Post newPost = new Post();
        newPost.addPendingAction(PendingAction.CREATE);
        newPost.setUuid(getTempUniqueId(Post.class));
        createOrUpdateModel(newPost);                    // save the local post to db
        getBus().post(new PostCreatedEvent(newPost));
        getBus().post(new SyncPostsEvent(false));
    }

    @Subscribe
    public void onSyncPostsEvent(final SyncPostsEvent event) {
        if (event.loadCachedData) {
            LoadPostsEvent loadPostsEvent = new LoadPostsEvent(false);
            mRefreshEventsQueue.add(loadPostsEvent);
            getBus().post(loadPostsEvent);
            refreshSucceeded(event);
            return;
        }

        final List<Post> localDeletedPosts = copyPosts(mRealm.where(Post.class)
                .equalTo("pendingActions.type", PendingAction.DELETE)
                .findAll());
        final List<Post> localNewPosts = copyPosts(mRealm.where(Post.class)
                .equalTo("pendingActions.type", PendingAction.CREATE)
                .findAllSorted("uuid", Sort.DESCENDING));
        final List<Post> localEditedPosts = copyPosts(mRealm.where(Post.class)
                .equalTo("pendingActions.type", PendingAction.EDIT)
                .findAll());

        // nothing to upload
        if (localDeletedPosts.isEmpty() && localNewPosts.isEmpty() && localEditedPosts.isEmpty()
                && event.forceNetworkCall) {
            LoadPostsEvent loadPostsEvent = new LoadPostsEvent(true);
            mRefreshEventsQueue.add(loadPostsEvent);
            getBus().post(loadPostsEvent);
            refreshSucceeded(event);
            return;
        }

        Deque<Post> mPostUploadQueue = new ArrayDeque<>();

        // keep track of new posts created successfully and posts deleted successfully, so the local copies can be deleted
        List<Post> postsToDelete = new ArrayList<>();

        // ugly hack (suggested by the IDE) because this must be declared "final"
        final Response[] uploadError = {null};

        final Action0 syncFinishedCB = () -> {
            // delete local copies
            if (!postsToDelete.isEmpty()) {
                RealmQuery<Post> deleteQuery = mRealm.where(Post.class);
                for (int i = 0; i < postsToDelete.size(); ++i) {
                    Post post = postsToDelete.get(i);
                    if (i > 0) deleteQuery.or();
                    deleteQuery.equalTo("uuid", post.getUuid());
                }
                deleteModels(deleteQuery.findAll());
            }

            // if forceNetworkCall is true, first load from the db, AND only then from the network,
            // to avoid a crash because local posts have been deleted above but are still being
            // displayed, so we need to refresh the UI first
            Response response = uploadError[0];
            if (NetworkUtils.isUnauthorized(response)) {
                // defer the event and try to re-authorize
                refreshAccessToken(event);
            } else {
                if (response.code() == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    refreshSucceeded(event);
                } else {
                    refreshFailed(event, error);
                }
                getBus().post(new PostsLoadedEvent(getPostsSorted(), POSTS_FETCH_LIMIT));
                if (event.forceNetworkCall) {
                    LoadPostsEvent loadPostsEvent = new LoadPostsEvent(true);
                    mRefreshEventsQueue.add(loadPostsEvent);
                    getBus().post(loadPostsEvent);
                }
            }
        };

        final Action2<Post, Response> onFailure = (post, response) -> {
            uploadError[0] = response;
            mPostUploadQueue.removeFirstOccurrence(post);
            getBus().post(new ApiErrorEvent(response));
            if (mPostUploadQueue.isEmpty()) syncFinishedCB.call();
        };

        mPostUploadQueue.addAll(localDeletedPosts);
        mPostUploadQueue.addAll(localNewPosts);
        mPostUploadQueue.addAll(localEditedPosts);

        // 1. DELETED POSTS
        // the loop variable is *local* to the loop block, so it can be captured in a closure easily
        // this is unlike JavaScript, in which the same loop variable is mutated
        for (final Post localPost : localDeletedPosts) {
            if (! validateAccessToken(event)) return;
            Crashlytics.log(Log.DEBUG, TAG, "[onSyncPostsEvent] deleting post id = " + localPost.getId());
            mApi.deletePost(mAuthToken.getAuthHeader(), localPost.getId()).enqueue(new Callback<String>() {
                @Override
                public void onResponse(Call<String> call, Response<String> response) {
                    if (response.isSuccessful()) {
                        AnalyticsService.logDraftDeleted();
                        mPostUploadQueue.removeFirstOccurrence(localPost);
                        postsToDelete.add(localPost);
                        if (mPostUploadQueue.isEmpty()) syncFinishedCB.call();
                    } else {
                        onFailure.call(localPost, response);
                    }
                }

                @Override
                public void onFailure(Call<String> call, Throwable error) {
                    onFailure.call(localPost, error);
                }
            });
        }

        // 2. NEW POSTS
        for (final Post localPost : localNewPosts) {
            if (! validateAccessToken(event)) return;
            Crashlytics.log(Log.DEBUG, TAG, "[onSyncPostsEvent] creating post");    // local new posts don't have an id
            mApi.createPost(mAuthToken.getAuthHeader(), PostStubList.from(localPost)).enqueue(new Callback<PostList>() {
                @Override
                public void onResponse(Call<PostList> call, Response<PostList> response) {
                    if (response.isSuccessful()) {
                        PostList postList = response.body();
                        AnalyticsService.logNewDraftUploaded();
                        createOrUpdateModel(postList.posts);
                        mPostUploadQueue.removeFirstOccurrence(localPost);
                        postsToDelete.add(localPost);
                        // FIXME this is a new post! how do subscribers know which post changed?
                        getBus().post(new PostReplacedEvent(postList.posts.get(0)));
                        if (mPostUploadQueue.isEmpty()) syncFinishedCB.call();
                    } else {
                        onFailure.call(localPost, response);
                    }
                }

                @Override
                public void onFailure(Call<PostList> call, Throwable error) {
                    onFailure.call(localPost, error);
                }
            });
        }

        // 3. EDITED POSTS
        Action1<Post> uploadEditedPost = (editedPost) -> {
            Crashlytics.log(Log.DEBUG, TAG, "[onSyncPostsEvent] updating post id = " + editedPost.getId());
            PostStubList postStubList = PostStubList.from(editedPost);
            mApi.updatePost(mAuthToken.getAuthHeader(), editedPost.getId(), postStubList).enqueue(new Callback<PostList>() {
                @Override
                public void onResponse(Call<PostList> call, Response<PostList> response) {
                    if (response.isSuccessful()) {
                        PostList postList = response.body();
                        createOrUpdateModel(postList.posts);
                        mPostUploadQueue.removeFirstOccurrence(editedPost);
                        getBus().post(new PostSyncedEvent(editedPost.getUuid()));
                        if (mPostUploadQueue.isEmpty()) syncFinishedCB.call();
                    } else {
                        onFailure.call(editedPost, response);
                    }
                }

                @Override
                public void onFailure(Call<PostList> call, Throwable error) {
                    onFailure.call(editedPost, error);
                }
            });
        };
        for (final Post localPost : localEditedPosts) {
            if (! validateAccessToken(event)) return;
            Crashlytics.log(Log.DEBUG, TAG, "[onSyncPostsEvent] downloading edited post with id = " + localPost.getId() + " for comparison");
            mApi.getPost(mAuthToken.getAuthHeader(), localPost.getId()).enqueue(new Callback<PostList>() {
                @Override
                public void onResponse(Call<PostList> call, Response<PostList> response) {
                    if (response.isSuccessful()) {
                        PostList postList = response.body();
                        Post serverPost = null;
                        boolean hasConflict = false;
                        if (!postList.posts.isEmpty()) {
                            serverPost = postList.posts.get(0);
                            hasConflict = (serverPost.getUpdatedAt() != null
                                    && !serverPost.getUpdatedAt().equals(localPost.getUpdatedAt()));
                        }
                        if (hasConflict && PostUtils.isDirty(serverPost, localPost)) {
                            Crashlytics.log(Log.WARN, TAG, "[onSyncPostsEvent] conflict found for post id = " + localPost.getId());
                            mPostUploadQueue.removeFirstOccurrence(localPost);
                            if (mPostUploadQueue.isEmpty()) syncFinishedCB.call();
                            localPost.setConflictState(Post.CONFLICT_UNRESOLVED);
                            createOrUpdateModel(localPost);
                            Crashlytics.log(Log.DEBUG, TAG, "localPost updated at:" + localPost.getUpdatedAt().toString());
                            Crashlytics.log(Log.DEBUG, TAG, "serverPost updated at: " + serverPost.getUpdatedAt().toString());
                            Crashlytics.log(Log.DEBUG, TAG, "localPost contents:\n" + localPost.getMarkdown());
                            Crashlytics.log(Log.DEBUG, TAG, "serverPost contents:\n" + serverPost.getMarkdown());
                            Crashlytics.logException(new PostConflictFoundException());
                            getBus().post(new PostConflictFoundEvent(localPost, serverPost));
                        } else {
                            uploadEditedPost.call(localPost);
                        }
                    } else {
                        // if we can't get the server post, optimistically upload the local copy
                        uploadEditedPost.call(localPost);
                    }
                }

                @Override
                public void onFailure(Call<PostList> call, Throwable error) {
                    // if we can't get the server post, optimistically upload the local copy
                    uploadEditedPost.call(localPost);
                }
            });
        }
    }

    @Subscribe
    public void onSavePostEvent(SavePostEvent event) {
        Post updatedPost = event.post;
        Post realmPost = mRealm.where(Post.class)
                .equalTo("id", event.post.getId())
                .findFirst();
        Crashlytics.log(Log.DEBUG, TAG, "[onSavePostEvent] post id = " + event.post.getId());

        if (realmPost.hasPendingAction(PendingAction.DELETE)) {
            RuntimeException e = new IllegalArgumentException("Trying to save deleted post with id = " + realmPost.getId());
            Crashlytics.logException(e);
        }

        // TODO bug in edge-case:
        // TODO 1. user publishes draft, offline => pending actions = { EDIT }
        // TODO 2. then makes some edits offline which are auto-saved => pending actions = { EDIT_LOCAL }
        // TODO the post will not actually be published now!
        // TODO to resolve this we would require some notion of pending actions associated with
        // TODO specific fields of a post rather than the entire post

        // save tags to Realm first
        for (Tag tag : updatedPost.getTags()) {
            if (tag.getUuid() == null) {
                tag.setUuid(getTempUniqueId(Tag.class));
                createOrUpdateModel(tag);
            }
        }

        // don't set updatedAt to enable easy conflict detection by comparing updatedAt values
        //updatedPost.setUpdatedAt(new Date());              // mark as updated, to promote in sorted order
        createOrUpdateModel(updatedPost);                  // save the local post to db

        // must set PendingActions after other stuff, else the updated post's pending actions will
        // override the one in Realm!
        //noinspection StatementWithEmptyBody
        if (realmPost.hasPendingAction(PendingAction.CREATE)) {
            // no-op; if the post is yet to be created, we DO NOT change the PendingAction on it
        } else if (realmPost.isDraft()) {
            clearAndSetPendingActionOnPost(realmPost, PendingAction.EDIT);
        } else if ((realmPost.isScheduled() || realmPost.isPublished()) && event.isAutoSave) {
            clearAndSetPendingActionOnPost(realmPost, PendingAction.EDIT_LOCAL);
        } else {
            // user hit "update" explicitly, on a scheduled or published post, so mark it for uploading
            clearAndSetPendingActionOnPost(realmPost, PendingAction.EDIT);
        }

        Post savedPost = new Post(realmPost);   // realmPost is guaranteed to be up-to-date
        getBus().post(new PostSavedEvent(savedPost));
        getBus().post(new SyncPostsEvent(false));
    }

    @Subscribe
    public void onDeletePostEvent(DeletePostEvent event) {
        int postId = event.post.getId();
        Crashlytics.log(Log.DEBUG, TAG, "[onDeletePostEvent] post id = " + postId);

        Post realmPost = mRealm.where(Post.class).equalTo("id", postId).findFirst();
        if (realmPost == null) {
            RuntimeException e = new IllegalArgumentException("Trying to delete post with non-existent id = " + postId);
            Crashlytics.logException(e);
        } else if (realmPost.hasPendingAction(PendingAction.CREATE)) {
            deleteModel(realmPost);
            getBus().post(new PostDeletedEvent(postId));
        } else {
            // don't delete locally until the remote copy is deleted
            clearAndSetPendingActionOnPost(realmPost, PendingAction.DELETE);
            getBus().post(new PostDeletedEvent(postId));

            // DON'T trigger a sync here, because it is automatically triggered by the post list anyway
            // triggering it twice causes crashes due to invalid Realm objects (deleted twice)
            //getBus().post(new SyncPostsEvent(false));
        }
    }

    @Subscribe
    public void onFileUploadEvent(FileUploadEvent event) {
        if (! validateAccessToken(event)) return;
        Crashlytics.log(Log.DEBUG, TAG, "[onFileUploadEvent] uploading file");

        TypedFile typedFile = new TypedFile(event.mimeType, new File(event.path));
        mApi.uploadFile(mAuthToken.getAuthHeader(), typedFile, new Callback<String>() {
            @Override
            public void success(String url, Response response) {
                getBus().post(new FileUploadedEvent(url));
            }

            @Override
            public void failure(RetrofitError error) {
                if (NetworkUtils.isUnauthorized(error)) {
                    // defer the event and try to re-authorize
                    refreshAccessToken(event);
                } else if (NetworkUtils.isRealError(error)) {
                    getBus().post(new FileUploadErrorEvent(error));
                    getBus().post(new ApiErrorEvent(error));
                }
            }
        });
    }

    @Subscribe
    public void onLoadTagsEvent(LoadTagsEvent event) {
        RealmResults<Tag> tags = mRealm.where(Tag.class).findAllSorted("name");
        List<Tag> tagsCopy = new ArrayList<>(tags.size());
        for (Tag tag : tags) {
            tagsCopy.add(new Tag(tag.getName()));
        }
        getBus().post(new TagsLoadedEvent(tagsCopy));
    }

    @Subscribe
    public void onLogoutEvent(LogoutEvent event) {
        if (!event.forceLogout) {
            long numPostsWithPendingActions = mRealm
                    .where(Post.class)
                    .isNotEmpty("pendingActions")
                    .count();
            if (numPostsWithPendingActions > 0) {
                getBus().post(new LogoutStatusEvent(false, true));
                return;
            }
        }

        // copy auth token before closing the Realm
        final AuthToken tokenToRevoke = new AuthToken(mAuthToken);

        // clear all persisted blog data to avoid primary key conflicts
        mRealm.close();
        Realm.deleteRealm(mRealm.getConfiguration());
        mRealm = Realm.getDefaultInstance();
        AppState.getInstance(SpectreApplication.getInstance())
                .setBoolean(AppState.Key.LOGGED_IN, false);
        // reset state, to be sure
        mApiEventQueue.clear();
        mRefreshEventsQueue.clear();
        mbAuthRequestOnGoing = false;
        mRefreshError = null;
        getBus().post(new LogoutStatusEvent(true, false));

        // revoke access and refresh tokens in the background
        GhostApiService apiServiceForClientSecret = buildApiService(event.blogUrl, false);
        GhostApiUtils.doWithClientSecret(apiServiceForClientSecret, clientSecret -> {
            RevokeReqBody revokeReqs[] = RevokeReqBody.from(tokenToRevoke, clientSecret);
            for (RevokeReqBody reqBody : revokeReqs) {
                mApi.revokeAuthToken(tokenToRevoke.getAuthHeader(), reqBody, new Callback<JsonElement>() {
                    @Override
                    public void success(JsonElement jsonResponse, Response response) {
                        JsonObject jsonObj = jsonResponse.getAsJsonObject();
                        if (jsonObj.has("error")) {
                            Crashlytics.logException(new TokenRevocationFailedException(
                                    reqBody.tokenTypeHint, jsonObj.get("error").getAsString()));
                        }
                    }

                    @Override
                    public void failure(RetrofitError error) {
                        Crashlytics.logException(new TokenRevocationFailedException(
                                reqBody.tokenTypeHint, error));
                    }
                });
            }
        });
    }


    // private methods
    private void clearAndSetPendingActionOnPost(@NonNull Post post, @PendingAction.Type String newPendingAction) {
        List<PendingAction> pendingActions = post.getPendingActions();
        mRealm.executeTransaction(realm -> {
            // make a copy since the original is a live-updating RealmList
            List<PendingAction> pendingActionsCopy = new ArrayList<>(pendingActions);
            for (PendingAction pa : pendingActionsCopy) {
                RealmObject.deleteFromRealm(pa);
            }
            pendingActions.clear();
            post.addPendingAction(newPendingAction);
        });
    }

    private boolean validateAccessToken(@NonNull ApiCallEvent event) {
        boolean valid = ! hasAccessTokenExpired();
        if (! valid) {
            refreshAccessToken(event);
        }
        return valid;
    }

    private void refreshAccessToken(@Nullable final ApiCallEvent eventToDefer) {
        if (eventToDefer != null) {
            mApiEventQueue.addLast(eventToDefer);
        }
        if (mbAuthRequestOnGoing) return;

        // don't waste bandwidth by trying to use an expired refresh token
        if (hasRefreshTokenExpired()) {
            postLoginStartEvent();
            return;
        }

        final RefreshReqBody credentials = new RefreshReqBody(mAuthToken.getRefreshToken());
        mbAuthRequestOnGoing = true;
        mApi.refreshAuthToken(credentials, new Callback<AuthToken>() {
            @Override
            public void success(AuthToken authToken, Response response) {
                // since this is a *refreshed* auth token, there is no refresh token in it, so add
                // it manually
                authToken.setRefreshToken(credentials.refreshToken);
                onNewAuthToken(authToken);
            }

            @Override
            public void failure(RetrofitError error) {
                mbAuthRequestOnGoing = false;
                // if the response is 401 Unauthorized, we can recover from it by logging in anew
                // this should only happen if the refresh token is manually revoked or the
                // expiration time is changed inside Ghost (#92)
                if (NetworkUtils.isUnauthorized(error)) {
                    postLoginStartEvent();
                    Crashlytics.logException(new ExpiredTokenUsedException(error));
                } else {
                    getBus().post(new LoginErrorEvent(error, null, false));
                    flushApiEventQueue(true);
                }
            }
        });
    }

    private void flushApiEventQueue(boolean loadCachedData) {
        Bus bus = getBus();
        boolean isQueueEmpty;
        while (! mApiEventQueue.isEmpty()) {
            ApiCallEvent event = mApiEventQueue.remove();
            isQueueEmpty = mApiEventQueue.isEmpty();
            if (loadCachedData) event.loadCachedData();
            bus.post(event);
            if (isQueueEmpty) {     // don't retry, gets into infinite loop
                mApiEventQueue.clear();
            }
        }
    }

    private void refreshSucceeded(@Nullable ApiCallEvent sourceEvent) {
        refreshDone(sourceEvent, null);
    }

    private void refreshFailed(@Nullable ApiCallEvent sourceEvent, @NonNull RetrofitError error) {
        refreshDone(sourceEvent, error);
    }

    private void refreshDone(@Nullable ApiCallEvent sourceEvent, @Nullable RetrofitError error) {
        mRefreshEventsQueue.removeFirstOccurrence(sourceEvent);
        if (error != null) {
            mRefreshError = error;      // turn on error flag if *any* request fails
        }
        if (mRefreshEventsQueue.isEmpty()) {
            getBus().post(new DataRefreshedEvent(mRefreshError));
            mRefreshError = null;       // clear last error if any
        }
    }

    private void clearSavedPassword() {
        UserPrefs prefs = UserPrefs.getInstance(SpectreApplication.getInstance());
        prefs.clear(UserPrefs.Key.PASSWORD);
        AppState appState = AppState.getInstance(SpectreApplication.getInstance());
        appState.clear(AppState.Key.LOGGED_IN);
    }

    private void savePermalinkFormat(List<Setting> settings) {
        for (Setting setting : settings) {
            if ("permalinks".equals(setting.getKey())) {
                UserPrefs.getInstance(SpectreApplication.getInstance())
                        .setString(UserPrefs.Key.PERMALINK_FORMAT, setting.getValue());
            }
        }
    }

    private void postLoginStartEvent() {
        UserPrefs prefs = UserPrefs.getInstance(SpectreApplication.getInstance());
        String blogUrl = prefs.getString(UserPrefs.Key.BLOG_URL);
        String username = prefs.getString(UserPrefs.Key.USERNAME);
        String password = prefs.getString(UserPrefs.Key.PASSWORD);
        getBus().post(new LoginStartEvent(blogUrl, username, password, false));
    }

    private void onNewAuthToken(AuthToken authToken) {
        Log.d(TAG, "Got new access token = " + authToken.getAccessToken());
        mbAuthRequestOnGoing = false;
        authToken.setCreatedAt(DateTimeUtils.getEpochSeconds());
        mAuthToken = createOrUpdateModel(authToken);
        AppState.getInstance(SpectreApplication.getInstance())
                .setBoolean(AppState.Key.LOGGED_IN, true);
        flushApiEventQueue(false);
    }

    private boolean hasAccessTokenExpired() {
        // consider the token as "expired" 60 seconds earlier, because the createdAt timestamp can
        // be off by several seconds
        return DateTimeUtils.getEpochSeconds() > mAuthToken.getCreatedAt() +
                mAuthToken.getExpiresIn() - 60;
    }

    private boolean hasRefreshTokenExpired() {
        // consider the token as "expired" 60 seconds earlier, because the createdAt timestamp can
        // be off by several seconds
        return DateTimeUtils.getEpochSeconds() > mAuthToken.getCreatedAt() + 86400 - 60;
    }

    private GhostApiService buildApiService(@NonNull String blogUrl, boolean useApiBaseUrl) {
        String baseUrl = blogUrl;
        if (useApiBaseUrl) {
            baseUrl = NetworkUtils.makeAbsoluteUrl(blogUrl, "ghost/api/v0.1/");
        }
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(mOkHttpClient)
                .addConverterFactory(mJSONObjectConverterFactory)
                .addConverterFactory(mGsonConverterFactory)
                .build();
        return retrofit.create(GhostApiService.class);
    }

    private List<Post> getPostsSorted() {
        // FIXME time complexity O(n) for copying + O(n log n) for sorting!
        RealmResults<Post> realmPosts = mRealm.where(Post.class).findAll();
        List<Post> unmanagedPosts = copyPosts(realmPosts);
        Collections.sort(unmanagedPosts, PostUtils.COMPARATOR_MAIN_LIST);
        return unmanagedPosts;
    }

    private void storeEtag(Headers headers, @ETag.Type String etagType) {
        for (String name : headers.names()) {
            if ("ETag".equals(name) && !headers.values(name).isEmpty()) {
                ETag etag = new ETag(etagType, headers.values(name).get(0));
                createOrUpdateModel(etag);
            }
        }
    }

    private String loadEtag(@ETag.Type String etagType) {
        ETag etag = mRealm.where(ETag.class).equalTo("type", etagType).findFirst();
        return (etag != null) ? etag.getTag() : "";
    }

    private void removeEtag(@ETag.Type String etagType) {
        RealmResults<ETag> etags = mRealm.where(ETag.class).equalTo("type", etagType).findAll();
        if (etags.size() > 0) {
            deleteModel(etags.first());
        }
    }

    /**
     * Generates a temporary primary key until the actual id is generated by the server. <b>Be
     * careful when calling this in a loop, if you don't save the object before calling it again,
     * you'll get the same id twice!</b>
     */
    @NonNull
    private <T extends RealmModel> String getTempUniqueId(Class<T> clazz) {
        int tempId = Integer.MAX_VALUE;
        while (mRealm.where(clazz).equalTo("uuid", String.valueOf(tempId)).findAll().size() > 0) {
            --tempId;
        }
        return String.valueOf(tempId);
    }

    private List<Post> copyPosts(List<Post> posts) {
        List<Post> copied = new ArrayList<>(posts.size());
        for (Post model : posts) {
            copied.add(new Post(model));
        }
        return copied;
    }

    private <T extends RealmModel> T createOrUpdateModel(T object) {
        return createOrUpdateModel(object, null);
    }

    private <T extends RealmModel> T createOrUpdateModel(T object,
                                                         @Nullable Runnable afterTransaction) {
        return executeRealmTransaction(mRealm, realm -> {
            T realmObject = mRealm.copyToRealmOrUpdate(object);
            if (afterTransaction != null) {
                afterTransaction.run();
            }
            return realmObject;
        });
    }

    private <T extends RealmModel> List<T> createOrUpdateModel(Iterable<T> objects) {
        return createOrUpdateModel(objects, null);
    }

    private <T extends RealmModel> List<T> createOrUpdateModel(Iterable<T> objects,
                                                               @Nullable Runnable afterTransaction) {
        if (! objects.iterator().hasNext()) {
            return Collections.emptyList();
        }
        return executeRealmTransaction(mRealm, realm -> {
            List<T> realmObjects = mRealm.copyToRealmOrUpdate(objects);
            if (afterTransaction != null) {
                afterTransaction.run();
            }
            return realmObjects;
        });
    }

    private <T extends RealmModel> void deleteModel(T realmObject) {
        executeRealmTransaction(mRealm, realm -> {
            RealmObject.deleteFromRealm(realmObject);
            return null;
        });
    }

    private <T extends RealmModel> void deleteModels(Iterable<T> realmObjects) {
        if (! realmObjects.iterator().hasNext()) {
            return;
        }
        executeRealmTransaction(mRealm, realm -> {
            for (T realmObject : realmObjects) {
                RealmObject.deleteFromRealm(realmObject);
            }
            return null;
        });
    }

    private static <T> T executeRealmTransaction(@NonNull Realm realm,
                                                 @NonNull RealmTransactionWithReturn<T> transaction) {
        T retValue;
        realm.beginTransaction();
        try {
            retValue = transaction.execute(realm);
            realm.commitTransaction();
        } catch (Throwable e) {
            if (realm.isInTransaction()) {
                realm.cancelTransaction();
            } else {
                Log.w(TAG, "Could not cancel transaction, not currently in a transaction.");
            }
            throw e;
        }
        return retValue;
    }

    private interface RealmTransactionWithReturn<T> {
        T execute(@NonNull Realm realm);
    }

    private Bus getBus() {
        return BusProvider.getBus();
    }

}
