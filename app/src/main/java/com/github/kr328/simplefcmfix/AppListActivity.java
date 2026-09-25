package com.github.kr328.simplefcmfix;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class AppListActivity extends Activity
        implements AppListController.Listener {
    private static final String STATE_QUERY = "query";
    private static final String STATE_SHOW_SYSTEM = "show_system";
    private static final String STATE_ONLY_FCM = "only_fcm";
    private static final String STATE_ONLY_PLAY_STORE = "only_play_store";
    private static final String STATE_ONLY_NO_RESTRICTION = "only_no_restriction";
    private static final String STATE_ONLY_AUTOSTART = "only_autostart";

    @Nullable
    private AppListController controller;
    @Nullable
    private AppListAdapter adapter;
    @Nullable
    private View emptyMatchesView;
    @Nullable
    private ProgressBar loadingProgress;
    @NonNull
    private List<AppListController.Entry> allEntries = List.of();
    @NonNull
    private String query = "";
    private boolean showSystem;
    private boolean onlyFcm;
    private boolean onlyPlayStore;
    private boolean onlyNoRestriction;
    private boolean onlyAutostart;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            query = savedInstanceState.getString(STATE_QUERY, "");
            showSystem = savedInstanceState.getBoolean(STATE_SHOW_SYSTEM);
            onlyFcm = savedInstanceState.getBoolean(STATE_ONLY_FCM);
            onlyPlayStore = savedInstanceState.getBoolean(STATE_ONLY_PLAY_STORE);
            onlyNoRestriction = savedInstanceState.getBoolean(STATE_ONLY_NO_RESTRICTION);
            onlyAutostart = savedInstanceState.getBoolean(STATE_ONLY_AUTOSTART);
        }

        setContentView(R.layout.app_list);

        final ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setElevation(0);
            actionBar.setDisplayShowTitleEnabled(false);

            final SearchView searchView = new SearchView(this);
            searchView.setIconifiedByDefault(false);
            searchView.setQueryHint(getString(R.string.app_list_search_hint));
            searchView.setQuery(query, false);
            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(final String submittedQuery) {
                    searchView.clearFocus();
                    return true;
                }

                @Override
                public boolean onQueryTextChange(final String newText) {
                    query = newText;
                    updateVisibleEntries();
                    return true;
                }
            });
            actionBar.setCustomView(searchView, new ActionBar.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.START | Gravity.CENTER_VERTICAL));
            actionBar.setDisplayShowCustomEnabled(true);
            searchView.clearFocus();
        }

        emptyMatchesView = findViewById(R.id.empty_matches);
        loadingProgress = findViewById(R.id.loading_progress);
        final ListView list = findViewById(android.R.id.list);
        list.setDivider(null);
        controller = new AppListController(this, this);
        adapter = new AppListAdapter(this, controller);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            final AppListController.Entry item =
                    (AppListController.Entry) parent.getItemAtPosition(position);

            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", item.status().packageName(), null)));
            } catch (final RuntimeException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (controller != null) {
            controller.refresh();
        }
    }

    @Override
    protected void onDestroy() {
        if (controller != null) {
            controller.close();
            controller = null;
        }

        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        outState.putString(STATE_QUERY, query);
        outState.putBoolean(STATE_SHOW_SYSTEM, showSystem);
        outState.putBoolean(STATE_ONLY_FCM, onlyFcm);
        outState.putBoolean(STATE_ONLY_PLAY_STORE, onlyPlayStore);
        outState.putBoolean(STATE_ONLY_NO_RESTRICTION, onlyNoRestriction);
        outState.putBoolean(STATE_ONLY_AUTOSTART, onlyAutostart);
        super.onSaveInstanceState(outState);
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull final Menu menu) {
        getMenuInflater().inflate(R.menu.app_list, menu);
        menu.findItem(R.id.filter_show_system).setChecked(showSystem);
        menu.findItem(R.id.filter_only_fcm).setChecked(onlyFcm);
        menu.findItem(R.id.filter_only_play_store).setChecked(onlyPlayStore);
        menu.findItem(R.id.filter_only_no_restriction).setChecked(onlyNoRestriction);
        menu.findItem(R.id.filter_only_autostart).setChecked(onlyAutostart);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }

        final int itemId = item.getItemId();
        if (itemId == R.id.filter_show_system) {
            showSystem = !showSystem;
            item.setChecked(showSystem);
        } else if (itemId == R.id.filter_only_fcm) {
            onlyFcm = !onlyFcm;
            item.setChecked(onlyFcm);
        } else if (itemId == R.id.filter_only_play_store) {
            onlyPlayStore = !onlyPlayStore;
            item.setChecked(onlyPlayStore);
        } else if (itemId == R.id.filter_only_no_restriction) {
            onlyNoRestriction = !onlyNoRestriction;
            item.setChecked(onlyNoRestriction);
        } else if (itemId == R.id.filter_only_autostart) {
            onlyAutostart = !onlyAutostart;
            item.setChecked(onlyAutostart);
        } else {
            return super.onOptionsItemSelected(item);
        }

        updateVisibleEntries();
        return true;
    }

    private void updateVisibleEntries() {
        final AppListAdapter currentAdapter = adapter;
        if (currentAdapter == null) {
            return;
        }

        final String search = query.toLowerCase(Locale.ROOT);
        final ArrayList<AppListController.Entry> visible = new ArrayList<>(allEntries.size());
        for (final AppListController.Entry entry : allEntries) {
            final AppStatus status = entry.status();
            if ((!showSystem && status.isSystemApp())
                    || (onlyFcm && !status.supportsFcm())
                    || (onlyPlayStore && !status.fromPlayStore())
                    || (onlyNoRestriction && !status.noRestriction())
                    || (onlyAutostart && !status.allowAutoStart())) {
                continue;
            }
            if (!search.isEmpty()
                    && !status.packageName().toLowerCase(Locale.ROOT).contains(search)
                    && !entry.title().toLowerCase(Locale.ROOT).contains(search)) {
                continue;
            }
            visible.add(entry);
        }

        currentAdapter.setItems(visible);
        if (emptyMatchesView != null) {
            emptyMatchesView.setVisibility(!allEntries.isEmpty() && visible.isEmpty()
                    ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onEntriesChanged(@NonNull final List<AppListController.Entry> entries) {
        if (adapter != null && !isDestroyed()) {
            allEntries = List.copyOf(entries);
            updateVisibleEntries();
        }
    }

    @Override
    public void onLoadingChanged(final boolean loading) {
        if (loadingProgress != null && !isDestroyed()) {
            loadingProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
    }

    public void onLoadFailed(@Nullable final String message) {
        if (!isDestroyed()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onIconLoaded(
            @NonNull final AppListController.Entry entry,
            @NonNull final Drawable icon) {
        if (adapter != null && !isDestroyed()) {
            adapter.setIcon(entry, icon);
        }
    }

    private static final class AppListAdapter extends BaseAdapter {
        @NonNull
        private final Context context;
        @NonNull
        private final LayoutInflater inflater;
        @NonNull
        private final AppListController controller;
        @NonNull
        private final IdentityHashMap<AppListController.Entry, WeakReference<ImageView>>
                boundIconViews = new IdentityHashMap<>();
        private final int secondaryTextColor;
        private final int colorPrimaryContainer;
        private final int colorOnPrimaryContainer;
        @NonNull
        private List<AppListController.Entry> items = List.of();

        private AppListAdapter(
                @NonNull final Context context,
                @NonNull final AppListController controller) {
            this.context = context;
            this.controller = controller;
            inflater = LayoutInflater.from(context);
            try (final TypedArray colors = context.obtainStyledAttributes(
                    new int[]{android.R.attr.textColorSecondary,
                            R.attr.colorPrimaryContainer, R.attr.colorOnPrimaryContainer})) {
                secondaryTextColor = colors.getColor(0, Color.GRAY);
                colorPrimaryContainer = colors.getColor(1,
                        context.getColor(R.color.color_primary_container));
                colorOnPrimaryContainer = colors.getColor(2,
                        context.getColor(R.color.color_on_primary_container));
            }
        }

        private void setItems(@NonNull final List<AppListController.Entry> items) {
            boundIconViews.clear();
            this.items = List.copyOf(items);
            notifyDataSetChanged();
        }

        private void setIcon(
                @NonNull final AppListController.Entry entry,
                @NonNull final Drawable icon) {
            final WeakReference<ImageView> reference = boundIconViews.get(entry);
            final ImageView iconView = reference == null ? null : reference.get();
            if (iconView != null && iconView.getTag() == entry) {
                iconView.setImageDrawable(icon);
            }
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public AppListController.Entry getItem(final int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(final int position) {
            return position;
        }

        @Override
        public View getView(
                final int position,
                @Nullable final View convertView,
                @NonNull final ViewGroup parent) {
            final View view;
            final ViewHolder holder;
            if (convertView == null) {
                view = inflater.inflate(R.layout.item_app, parent, false);
                holder = new ViewHolder(
                        view.findViewById(R.id.icon),
                        view.findViewById(R.id.title),
                        view.findViewById(R.id.subtitle),
                        view.findViewById(R.id.status_chips),
                        view.findViewById(R.id.status_fcm),
                        view.findViewById(R.id.status_play_store),
                        view.findViewById(R.id.status_no_restriction),
                        view.findViewById(R.id.status_autostart));
                configureChip(holder.fcm(), R.drawable.ic_fcm, false, 14);
                configureChip(holder.playStore(), R.drawable.ic_play_store, false, 12);
                configureChip(holder.noRestriction(), R.drawable.ic_no_restrict, true, 16);
                configureChip(holder.autostart(), R.drawable.ic_autostart, true, 16);
                view.setTag(holder);
            } else {
                view = convertView;
                holder = (ViewHolder) view.getTag();
            }

            final AppListController.Entry item = getItem(position);
            final AppStatus status = item.status();
            holder.icon().setTag(item);
            boundIconViews.put(item, new WeakReference<>(holder.icon()));
            holder.icon().setImageDrawable(controller.getIconOrRequest(item));
            if (status.isSystemApp()) {
                final String suffix = context.getString(R.string.system_app_suffix);
                final SpannableString title = new SpannableString(item.title() + suffix);
                title.setSpan(new ForegroundColorSpan(secondaryTextColor),
                        item.title().length(), title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                holder.title().setText(title);
            } else {
                holder.title().setText(item.title());
            }
            holder.subtitle().setText(status.packageName());
            holder.fcm().setVisibility(status.supportsFcm() ? View.VISIBLE : View.GONE);
            holder.playStore().setVisibility(status.fromPlayStore() ? View.VISIBLE : View.GONE);
            holder.noRestriction().setVisibility(status.noRestriction() ? View.VISIBLE : View.GONE);
            holder.autostart().setVisibility(status.allowAutoStart() ? View.VISIBLE : View.GONE);
            holder.chips().setVisibility(
                    status.supportsFcm() || status.fromPlayStore()
                            || status.noRestriction() || status.allowAutoStart()
                            ? View.VISIBLE : View.GONE);

            return view;
        }

        private void configureChip(
                @NonNull final TextView chip,
                final int iconId,
                final boolean tintIcon,
                final int size
        ) {
            final GradientDrawable background = new GradientDrawable();
            background.setColor(colorPrimaryContainer);
            background.setCornerRadius(dp(16));
            chip.setBackground(background);

            final Drawable icon = Objects.requireNonNull(context.getDrawable(iconId)).mutate();
            icon.setBounds(0, 0, dp(size), dp(size));
            if (tintIcon) {
                icon.setTint(colorOnPrimaryContainer);
            }
            chip.setCompoundDrawablesRelative(icon, null, null, null);
            chip.setCompoundDrawablePadding(dp(4));
        }

        private int dp(final int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }
    }

    private record ViewHolder(
            @NonNull ImageView icon,
            @NonNull TextView title,
            @NonNull TextView subtitle,
            @NonNull View chips,
            @NonNull TextView fcm,
            @NonNull TextView playStore,
            @NonNull TextView noRestriction,
            @NonNull TextView autostart) {
    }
}
