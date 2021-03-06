package me.echeung.listenmoeapi;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import me.echeung.listenmoeapi.auth.AuthUtil;
import me.echeung.listenmoeapi.cache.SongsCache;
import me.echeung.listenmoeapi.callbacks.ArtistCallback;
import me.echeung.listenmoeapi.callbacks.ArtistsCallback;
import me.echeung.listenmoeapi.callbacks.AuthCallback;
import me.echeung.listenmoeapi.callbacks.FavoriteSongCallback;
import me.echeung.listenmoeapi.callbacks.RequestSongCallback;
import me.echeung.listenmoeapi.callbacks.SearchCallback;
import me.echeung.listenmoeapi.callbacks.SongsCallback;
import me.echeung.listenmoeapi.callbacks.UserFavoritesCallback;
import me.echeung.listenmoeapi.callbacks.UserInfoCallback;
import me.echeung.listenmoeapi.models.Song;
import me.echeung.listenmoeapi.models.SongListItem;
import me.echeung.listenmoeapi.responses.ArtistResponse;
import me.echeung.listenmoeapi.responses.ArtistsResponse;
import me.echeung.listenmoeapi.responses.AuthResponse;
import me.echeung.listenmoeapi.responses.BaseResponse;
import me.echeung.listenmoeapi.responses.FavoritesResponse;
import me.echeung.listenmoeapi.responses.Messages;
import me.echeung.listenmoeapi.responses.SongsResponse;
import me.echeung.listenmoeapi.responses.UserResponse;
import me.echeung.listenmoeapi.services.ArtistsService;
import me.echeung.listenmoeapi.services.AuthService;
import me.echeung.listenmoeapi.services.FavoritesService;
import me.echeung.listenmoeapi.services.RequestsService;
import me.echeung.listenmoeapi.services.SongsService;
import me.echeung.listenmoeapi.services.UsersService;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class APIClient {

    private static final String TAG = APIClient.class.getSimpleName();

    private static final String BASE_URL = "https://listen.moe/api/";
    public static final String CDN_ALBUM_ART_URL = "https://cdn.listen.moe/covers/";
    public static final String CDN_AVATAR_URL = "https://cdn.listen.moe/avatars/";
    public static final String CDN_BANNER_URL = "https://cdn.listen.moe/banners/";

    private static final String HEADER_USER_AGENT = "User-Agent";
    public static final String USER_AGENT = "me.echeung.moemoekyun";

    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_TYPE = "application/json";

    private static final String HEADER_ACCEPT = "Accept";
    private static final String ACCEPT = "application/vnd.listen.v4+json";

    private static Retrofit retrofit;

    private final AuthUtil authUtil;

    private final ArtistsService artistsService;
    private final AuthService authService;
    private final FavoritesService favoritesService;
    private final RequestsService requestsService;
    private final SongsService songsService;
    private final UsersService usersService;

    private final SongsCache songsCache;

    private final RadioSocket socket;
    private final RadioStream stream;

    public APIClient(Context context, AuthUtil authUtil) {
        this.authUtil = authUtil;

        final OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .addNetworkInterceptor(chain -> {
                    final Request request = chain.request();

                    final Request newRequest = request.newBuilder()
                            .addHeader(HEADER_USER_AGENT, USER_AGENT)
                            .addHeader(HEADER_CONTENT_TYPE, CONTENT_TYPE)
                            .addHeader(HEADER_ACCEPT, ACCEPT)
                            .build();

                    return chain.proceed(newRequest);
                })
                .build();

        retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addCallAdapterFactory(new ErrorHandlingAdapter.ErrorHandlingCallAdapterFactory())
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        artistsService = retrofit.create(ArtistsService.class);
        authService = retrofit.create(AuthService.class);
        favoritesService = retrofit.create(FavoritesService.class);
        requestsService = retrofit.create(RequestsService.class);
        songsService = retrofit.create(SongsService.class);
        usersService = retrofit.create(UsersService.class);

        songsCache = new SongsCache(this);

        socket = new RadioSocket(okHttpClient, authUtil);
        stream = new RadioStream(context);
    }

    /**
     * Authenticates to the radio.
     *
     * @param username User's username.
     * @param password User's password.
     * @param callback Listener to handle the response.
     */
    public void authenticate(final String username, final String password, final AuthCallback callback) {
        authService.login(new AuthService.LoginBody(username, password))
                .enqueue(new ErrorHandlingAdapter.WrappedCallback<AuthResponse>() {
                    @Override
                    public void success(final AuthResponse response) {
                        final String userToken = response.getToken();

                        if (response.isMfa()) {
                            callback.onMfaRequired(userToken);
                            return;
                        }

                        callback.onSuccess(userToken);
                    }

                    @Override
                    public void error(final String message) {
                        Log.e(TAG, message);
                        callback.onFailure(message);
                    }
                });
    }

    /**
     * Second step for MFA authentication.
     *
     * @param otpToken User's one-time password token.
     * @param callback Listener to handle the response.
     */
    public void authenticateMfa(final String otpToken, final AuthCallback callback) {
        authService.mfa(authUtil.getAuthTokenWithPrefix(), new AuthService.LoginMfaBody(otpToken))
                .enqueue(new ErrorHandlingAdapter.WrappedCallback<AuthResponse>() {
                    @Override
                    public void success(final AuthResponse response) {
                        final String userToken = response.getToken();
                        callback.onSuccess(userToken);
                    }

                    @Override
                    public void error(final String message) {
                        Log.e(TAG, message);
                        callback.onFailure(message);
                    }
                });
    }

    /**
     * Gets the user information (id and username).
     *
     * @param callback Listener to handle the response.
     */
    public void getUserInfo(final UserInfoCallback callback) {
        if (!authUtil.isAuthenticated()) {
            callback.onFailure(Messages.AUTH_ERROR);
            return;
        }

        usersService.getUserInfo(authUtil.getAuthTokenWithPrefix(), "@me")
                .enqueue(new ErrorHandlingAdapter.WrappedCallback<UserResponse>() {
                    @Override
                    public void success(final UserResponse response) {
                        callback.onSuccess(response.getUser());
                    }

                    @Override
                    public void error(final String message) {
                        Log.e(TAG, message);
                        callback.onFailure(message);
                    }
                });
    }

    /**
     * Gets a list of all the user's favorited songs.
     *
     * @param callback Listener to handle the response.
     */
    public void getUserFavorites(final UserFavoritesCallback callback) {
        if (!authUtil.isAuthenticated()) {
            callback.onFailure(Messages.AUTH_ERROR);
            return;
        }

        favoritesService.getFavorites(authUtil.getAuthTokenWithPrefix(), "@me")
                .enqueue(new ErrorHandlingAdapter.WrappedCallback<FavoritesResponse>() {
                    @Override
                    public void success(final FavoritesResponse response) {
                        List<Song> favorites = response.getFavorites();
                        for (Song song : favorites) {
                            song.setFavorite(true);
                        }
                        callback.onSuccess(favorites);
                    }

                    @Override
                    public void error(final String message) {
                        Log.e(TAG, message);
                        callback.onFailure(message);
                    }
                });
    }

    /**
     * Toggles a song's favorite status
     *
     * @param songId Song to update favorite status of.
     * @param isFavorite Whether the song is currently favorited.
     * @param callback Listener to handle the response.
     */
    public void toggleFavorite(final String songId, final boolean isFavorite, final FavoriteSongCallback callback) {
        if (isFavorite) {
            unfavoriteSong(songId, callback);
        } else {
            favoriteSong(songId, callback);
        }
    }

    /**
     * Favorites a song.
     *
     * @param songId   Song to favorite.
     * @param callback Listener to handle the response.
     */
    public void favoriteSong(final String songId, final FavoriteSongCallback callback) {
        if (!authUtil.isAuthenticated()) {
            callback.onFailure(Messages.AUTH_ERROR);
            return;
        }

        favoritesService.favorite(authUtil.getAuthTokenWithPrefix(), songId)
                .enqueue(new ErrorHandlingAdapter.WrappedCallback<BaseResponse>() {
                    @Override
                    public void success(final BaseResponse response) {
                        callback.onSuccess();
                    }

                    @Override
                    public void error(final String message) {
                        Log.e(TAG, message);
                        callback.onFailure(message);
                    }
                });
    }

    /**
     * Unfavorites a song.
     *
     * @param songId   Song to unfavorite.
     * @param callback Listener to handle the response.
     */
    public void unfavoriteSong(final String songId, final FavoriteSongCallback callback) {
        if (!authUtil.isAuthenticated()) {
            callback.onFailure(Messages.AUTH_ERROR);
            return;
        }

        favoritesService.removeFavorite(authUtil.getAuthTokenWithPrefix(), songId)
                .enqueue(new ErrorHandlingAdapter.WrappedCallback<BaseResponse>() {
                    @Override
                    public void success(final BaseResponse response) {
                        callback.onSuccess();
                    }

                    @Override
                    public void error(final String message) {
                        Log.e(TAG, message);
                        callback.onFailure(message);
                    }
                });
    }

    /**
     * Sends a song request to the queue.
     *
     * @param songId   Song to request.
     * @param callback Listener to handle the response.
     */
    public void requestSong(final String songId, final RequestSongCallback callback) {
        if (!authUtil.isAuthenticated()) {
            callback.onFailure(Messages.AUTH_ERROR);
            return;
        }

        requestsService.request(authUtil.getAuthTokenWithPrefix(), songId)
                .enqueue(new ErrorHandlingAdapter.WrappedCallback<BaseResponse>() {
                    @Override
                    public void success(final BaseResponse response) {
                        callback.onSuccess();
                    }

                    @Override
                    public void error(final String message) {
                        Log.e(TAG, message);
                        callback.onFailure(message);
                    }
                });
    }

    /**
     * Gets all songs.
     *
     * @param callback Listener to handle the response.
     */
    public void getSongs(final SongsCallback callback) {
        if (!authUtil.isAuthenticated()) {
            callback.onFailure(Messages.AUTH_ERROR);
            return;
        }

        songsService.getSongs(authUtil.getAuthTokenWithPrefix())
                .enqueue(new ErrorHandlingAdapter.WrappedCallback<SongsResponse>() {
                    @Override
                    public void success(final SongsResponse response) {
                        callback.onSuccess(response.getSongs());
                    }

                    @Override
                    public void error(final String message) {
                        Log.e(TAG, message);
                        callback.onFailure(message);
                    }
                });
    }

    /**
     * Searches for songs.
     *
     * @param query    Search query string.
     * @param callback Listener to handle the response.
     */
    public void search(final String query, final SearchCallback callback) {
        if (!authUtil.isAuthenticated()) {
            callback.onFailure(Messages.AUTH_ERROR);
            return;
        }

        songsCache.getSongs(new SongsCache.Callback() {
            @Override
            public void onRetrieve(List<SongListItem> songs) {
                List<Song> filteredSongs = filterSongs(songs, query);
                callback.onSuccess(filteredSongs);
            }

            @Override
            public void onFailure(final String message) {
                Log.e(TAG, message);
                callback.onFailure(message);
            }
        });
    }

    /**
     * Gets a list of all artists.
     *
     * @param callback Listener to handle the response.
     */
    public void getArtists(final ArtistsCallback callback) {
        if (!authUtil.isAuthenticated()) {
            callback.onFailure(Messages.AUTH_ERROR);
            return;
        }

        artistsService.getArtists(authUtil.getAuthTokenWithPrefix())
                .enqueue(new ErrorHandlingAdapter.WrappedCallback<ArtistsResponse>() {
                    @Override
                    public void success(final ArtistsResponse response) {
                        callback.onSuccess(response.getArtists());
                    }

                    @Override
                    public void error(final String message) {
                        Log.e(TAG, message);
                        callback.onFailure(message);
                    }
                });
    }

    /**
     * Gets an artist's info.
     *
     * @param artistId Artist to get.
     * @param callback Listener to handle the response.
     */
    public void getArtist(final String artistId, final ArtistCallback callback) {
        if (!authUtil.isAuthenticated()) {
            callback.onFailure(Messages.AUTH_ERROR);
            return;
        }

        artistsService.getArtist(authUtil.getAuthTokenWithPrefix(), artistId)
                .enqueue(new ErrorHandlingAdapter.WrappedCallback<ArtistResponse>() {
                    @Override
                    public void success(final ArtistResponse response) {
                        callback.onSuccess(response.getArtist());
                    }

                    @Override
                    public void error(final String message) {
                        Log.e(TAG, message);
                        callback.onFailure(message);
                    }
                });
    }

    private List<Song> filterSongs(List<SongListItem> songs, String query) {
        List<Song> filteredSongs = new ArrayList<>();

        for (SongListItem song : songs) {
            if (song.search(query)) {
                filteredSongs.add(SongListItem.toSong(song));
            }
        }

        return filteredSongs;
    }

    public RadioSocket getSocket() {
        return socket;
    }

    public RadioStream getStream() {
        return stream;
    }

    static Retrofit getRetrofit() {
        return retrofit;
    }
}
