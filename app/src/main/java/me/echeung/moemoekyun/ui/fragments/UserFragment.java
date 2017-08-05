package me.echeung.moemoekyun.ui.fragments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.databinding.BaseObservable;
import android.databinding.DataBindingUtil;
import android.databinding.ObservableField;
import android.databinding.ObservableInt;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

import me.echeung.moemoekyun.R;
import me.echeung.moemoekyun.adapters.SongAdapter;
import me.echeung.moemoekyun.databinding.UserFragmentBinding;
import me.echeung.moemoekyun.interfaces.UserFavoritesListener;
import me.echeung.moemoekyun.interfaces.UserInfoListener;
import me.echeung.moemoekyun.model.Song;
import me.echeung.moemoekyun.model.SongsList;
import me.echeung.moemoekyun.model.UserInfo;
import me.echeung.moemoekyun.ui.activities.MainActivity;
import me.echeung.moemoekyun.ui.fragments.base.TabFragment;
import me.echeung.moemoekyun.util.APIUtil;
import me.echeung.moemoekyun.util.AuthUtil;
import me.echeung.moemoekyun.util.SongActionsUtil;

public class UserFragment extends TabFragment implements SongAdapter.OnSongItemClickListener {

    // Data binding state
    public static final UserFragment.UserState USER_STATE = new UserFragment.UserState();

    private LinearLayout vLoginMsg;
    private LinearLayout vUserContent;
    private ImageView vUserAvatar;

    // Favorites
    private List<Song> favorites;
    private SongAdapter adapter;

    // Receiver
    private IntentFilter intentFilter;
    private BroadcastReceiver intentReceiver;

    public static Fragment newInstance(int sectionNumber) {
        return TabFragment.newInstance(sectionNumber, new UserFragment());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        final UserFragmentBinding binding = DataBindingUtil.inflate(inflater, R.layout.user_fragment, container, false);
        binding.setUserName(USER_STATE.userName);
        binding.setUserRequests(USER_STATE.userRequests);

        vLoginMsg = binding.loginMsg;
        vUserContent = binding.userContent;

        final View view = binding.getRoot();
        vUserAvatar = binding.userAvatar;
        binding.btnLogin.setOnClickListener(v -> ((MainActivity) getActivity()).showLoginDialog());

        // TODO: favorites list should update

        // Favorites list adapter
        adapter = new SongAdapter(this);
        binding.userFavorites.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.userFavorites.setAdapter(adapter);

        // Set up favorites filtering
        binding.filterQuery.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                final String query = editable.toString().trim().toLowerCase();

                if (query.isEmpty()) {
                    adapter.setSongs(favorites);
                } else {
                    final List<Song> filteredFavorites = new ArrayList<>();
                    for (final Song song : favorites) {
                        if (song.getTitle().toLowerCase().contains(query) ||
                                song.getArtistAndAnime().toLowerCase().contains(query)) {
                            filteredFavorites.add(song);
                        }
                    }
                    adapter.setSongs(filteredFavorites);
                }
            }
        });

        // Broadcast receiver
        initBroadcastReceiver();

        initUserContent();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        LocalBroadcastManager.getInstance(getContext())
                .registerReceiver(intentReceiver, intentFilter);
    }

    @Override
    public void onPause() {
        super.onPause();

        LocalBroadcastManager.getInstance(getContext())
                .unregisterReceiver(intentReceiver);
    }

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(getContext())
                .unregisterReceiver(intentReceiver);

        super.onDestroy();
    }

    private void initBroadcastReceiver() {
        intentReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                if (action != null) {
                    switch (action) {
                        case MainActivity.AUTH_EVENT:
                            initUserContent();

                            // TODO: update favorite = false when logged out
                            break;
                    }
                }
            }
        };

        intentFilter = new IntentFilter();
        intentFilter.addAction(MainActivity.AUTH_EVENT);

        LocalBroadcastManager.getInstance(getContext())
                .registerReceiver(intentReceiver, intentFilter);
    }

    private void initUserContent() {
        boolean authenticated = AuthUtil.isAuthenticated(getContext());

        vLoginMsg.setVisibility(authenticated ? View.GONE : View.VISIBLE);
        vUserContent.setVisibility(authenticated ? View.VISIBLE : View.GONE);

        if (!authenticated) {
            return;
        }

        APIUtil.getUserInfo(getContext(), new UserInfoListener() {
            @Override
            public void onFailure(final String result) {
            }

            @Override
            public void onSuccess(final UserInfo userInfo) {
                getActivity().runOnUiThread(() -> {
                    final String userName = userInfo.getUsername();

                    USER_STATE.userName.set(userName);

                    // TODO: user avatars/banners are coming in v4
//                    APIUtil.getUserAvatar(getContext(), userName, new UserForumInfoListener() {
//                        @Override
//                        public void onFailure(final String result) {
//                        }
//
//                        @Override
//                        public void onSuccess(final String avatarUrl) {
//                            if (avatarUrl != null) {
//                                getActivity().runOnUiThread(() -> new DownloadImageTask(userAvatar).execute(avatarUrl));
//                            }
//                        }
//                    });
                });
            }
        });

        APIUtil.getUserFavorites(getContext(), new UserFavoritesListener() {
            @Override
            public void onFailure(final String result) {
            }

            @Override
            public void onSuccess(final SongsList songsList) {
                getActivity().runOnUiThread(() -> {
                    favorites = songsList.getSongs();
                    adapter.setSongs(favorites);
                    USER_STATE.userRequests.set(songsList.getExtra().getRequests());
                });
            }
        });
    }

    @Override
    public void onSongItemClick(final Song song) {
        // Create button "Favorite"/"Unfavorite"
        final String favoriteAction = song.isFavorite() ?
                getString(R.string.action_unfavorite) :
                getString(R.string.action_favorite);

        final AlertDialog.Builder builder = new AlertDialog.Builder(getContext())
                .setTitle(song.getTitle())
                .setMessage(song.getArtistAndAnime())
                .setPositiveButton(android.R.string.cancel, null)
                .setNegativeButton(favoriteAction, (dialogInterface, in) -> SongActionsUtil.favorite(getActivity(), adapter, song));

        if (song.isEnabled()) {
            // Create button "Request"
            builder.setNeutralButton(getString(R.string.action_request), (dialogInterface, im) -> SongActionsUtil.request(getActivity(), adapter, song));
        }

        builder.create().show();
    }

    public static class UserState extends BaseObservable {
        public final ObservableField<String> userName = new ObservableField<>();
        public final ObservableInt userRequests = new ObservableInt();
//        public final ObservableList<Song> favorites
    }
}
