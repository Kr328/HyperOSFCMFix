package com.github.kr328.simplefcmfix;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AllowlistActivity extends Activity
        implements ShizukuHelper.OnStateChangedListener {
    @NonNull
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    @Nullable
    private ShizukuHelper shizukuHelper;
    @Nullable
    private AllowlistAdapter adapter;
    private int loadGeneration;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.battery_optimization_allowlist);

        final ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setElevation(0);
        }

        final ListView list = findViewById(android.R.id.list);
        list.setDivider(null);
        adapter = new AllowlistAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            final AppEntry item = (AppEntry) parent.getItemAtPosition(position);

            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", item.packageName(), null)));
            } catch (final RuntimeException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        shizukuHelper = new ShizukuHelper(this);
        shizukuHelper.addOnStateChangedListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        updateAllowlist();
    }

    @Override
    protected void onDestroy() {
        ++loadGeneration;

        if (shizukuHelper != null) {
            shizukuHelper.removeOnStateChangedListener(this);
            shizukuHelper.close();
            shizukuHelper = null;
        }
        executor.shutdownNow();

        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onShizukuStateChanged() {
        runOnUiThread(() -> {
            if (!isDestroyed()) {
                updateAllowlist();
            }
        });
    }

    private void updateAllowlist() {
        final ShizukuHelper helper = shizukuHelper;
        final AllowlistAdapter currentAdapter = adapter;
        if (helper == null || currentAdapter == null) {
            return;
        }

        final ShizukuHelper.State state = helper.getState();
        if (!(state instanceof final ShizukuHelper.State.Ready ready)) {
            ++loadGeneration;
            currentAdapter.setItems(List.of());
            return;
        }

        final int generation = ++loadGeneration;
        executor.execute(() -> {
            try {
                final List<AppEntry> entries = loadEntries(ready.remote.getNoRestrictApps());
                runOnUiThread(() -> {
                    if (!isDestroyed() && generation == loadGeneration) {
                        Objects.requireNonNull(adapter).setItems(entries);
                    }
                });
            } catch (final RemoteException | RuntimeException e) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && generation == loadGeneration) {
                        Objects.requireNonNull(adapter).setItems(List.of());
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    @NonNull
    private List<AppEntry> loadEntries(@Nullable final String[] packageNames) {
        if (packageNames == null) {
            return List.of();
        }

        final LinkedHashSet<String> normalizedPackageNames = new LinkedHashSet<>();
        for (final String packageName : packageNames) {
            if (packageName != null && !packageName.trim().isEmpty()) {
                normalizedPackageNames.add(packageName.trim());
            }
        }

        final PackageManager packageManager = getPackageManager();
        final ArrayList<AppEntry> entries = new ArrayList<>(normalizedPackageNames.size());
        for (final String packageName : normalizedPackageNames) {
            try {
                final ApplicationInfo applicationInfo = packageManager.getApplicationInfo(
                        packageName, PackageManager.ApplicationInfoFlags.of(0));
                entries.add(new AppEntry(
                        applicationInfo.loadIcon(packageManager),
                        applicationInfo.loadLabel(packageManager),
                        packageName));
            } catch (final PackageManager.NameNotFoundException | RuntimeException ignored) {
                entries.add(new AppEntry(
                        Objects.requireNonNull(getDrawable(android.R.drawable.sym_def_app_icon)),
                        getText(R.string.unknown_app),
                        packageName));
            }
        }

        return entries;
    }

    private record AppEntry(
            @NonNull Drawable icon,
            @NonNull CharSequence title,
            @NonNull String packageName) {
    }

    private static final class AllowlistAdapter extends BaseAdapter {
        @NonNull
        private final LayoutInflater inflater;
        @NonNull
        private List<AppEntry> items = List.of();

        private AllowlistAdapter(@NonNull final Context context) {
            inflater = LayoutInflater.from(context);
        }

        private void setItems(@NonNull final List<AppEntry> items) {
            this.items = List.copyOf(items);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public AppEntry getItem(final int position) {
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
                view = inflater.inflate(R.layout.item_battery_optimization_allowlist,
                        parent, false);
                holder = new ViewHolder(
                        view.findViewById(R.id.icon),
                        view.findViewById(R.id.title),
                        view.findViewById(R.id.subtitle));
                view.setTag(holder);
            } else {
                view = convertView;
                holder = (ViewHolder) view.getTag();
            }

            final AppEntry item = getItem(position);
            holder.icon.setImageDrawable(item.icon());
            holder.title.setText(item.title());
            holder.subtitle.setText(item.packageName());

            return view;
        }
    }

    private record ViewHolder(
            @NonNull ImageView icon,
            @NonNull TextView title,
            @NonNull TextView subtitle) {
    }
}
