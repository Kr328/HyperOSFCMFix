package com.github.kr328.simplefcmfix;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class AppListLoader implements ShizukuHelper.OnStateChangedListener, AutoCloseable {
    @NonNull
    private final Context context;
    @NonNull
    private final PackageManager packageManager;
    @NonNull
    private final Listener listener;
    @NonNull
    private final ShizukuHelper shizukuHelper;
    @NonNull
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @NonNull
    private final ExecutorService dataExecutor = Executors.newSingleThreadExecutor();
    @NonNull
    private final ExecutorService iconExecutor = Executors.newFixedThreadPool(2);
    private final AtomicInteger loadGeneration = new AtomicInteger(0);
    private final AtomicInteger entriesGeneration = new AtomicInteger(0);
    private boolean closed;

    AppListLoader(@NonNull final Context context, @NonNull final Listener listener) {
        this.context = context;
        this.packageManager = context.getPackageManager();
        this.listener = listener;
        shizukuHelper = new ShizukuHelper(context);
        shizukuHelper.addOnStateChangedListener(this);
    }

    void refresh() {
        if (closed) {
            return;
        }

        final ShizukuHelper.State state = shizukuHelper.getState();
        if (!(state instanceof final ShizukuHelper.State.Ready ready)) {
            loadGeneration.incrementAndGet();
            listener.onLoadingChanged(false);
            publish(List.of());
            return;
        }

        final int generation = loadGeneration.incrementAndGet();
        listener.onLoadingChanged(true);
        dataExecutor.execute(() -> {
            try {
                final List<Entry> entries = loadEntries(ready.remote.listApps());
                mainHandler.post(() -> {
                    if (!closed && generation == loadGeneration.get()) {
                        listener.onLoadingChanged(false);
                        publish(entries);
                    }
                });
            } catch (final RemoteException | RuntimeException e) {
                mainHandler.post(() -> {
                    if (!closed && generation == loadGeneration.get()) {
                        listener.onLoadingChanged(false);
                        publish(List.of());
                        listener.onLoadFailed(e.getMessage());
                    }
                });
            }
        });
    }

    @Nullable
    Drawable getIconOrRequest(@NonNull final Entry entry) {
        if (closed || entry.generation != entriesGeneration.get()) {
            return null;
        }
        if (entry.icon != null || entry.iconRequested) {
            return entry.icon;
        }

        entry.iconRequested = true;
        final int generation = entriesGeneration.get();
        iconExecutor.execute(() -> {
            if (generation != entriesGeneration.get()) {
                return;
            }

            Drawable icon = null;
            try {
                if (entry.applicationInfo != null) {
                    icon = entry.applicationInfo.loadIcon(packageManager);
                }
            } catch (final RuntimeException ignored) {
                // The package may have been removed since the list was loaded.
            }
            if (icon == null) {
                icon = Objects.requireNonNull(
                        context.getDrawable(android.R.drawable.sym_def_app_icon));
            }

            final Drawable loadedIcon = icon;
            mainHandler.post(() -> {
                if (closed || generation != entriesGeneration.get()) {
                    return;
                }

                entry.icon = loadedIcon;
                listener.onIconLoaded(entry, loadedIcon);
            });
        });
        return null;
    }

    @Override
    public void onShizukuStateChanged() {
        mainHandler.post(this::refresh);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;
        loadGeneration.incrementAndGet();
        entriesGeneration.incrementAndGet();
        shizukuHelper.removeOnStateChangedListener(this);
        shizukuHelper.close();
        dataExecutor.shutdownNow();
        iconExecutor.shutdownNow();
    }

    private void publish(@NonNull final List<Entry> entries) {
        final int generation = entriesGeneration.incrementAndGet();
        for (final Entry entry : entries) {
            entry.generation = generation;
        }
        listener.onEntriesChanged(entries);
    }

    @NonNull
    private List<Entry> loadEntries(@Nullable final AppStatus[] statuses) {
        if (statuses == null) {
            return List.of();
        }

        final ArrayList<Entry> entries = new ArrayList<>(statuses.length);
        for (final AppStatus status : statuses) {
            if (status == null || status.packageName().isEmpty()) {
                continue;
            }

            try {
                final ApplicationInfo applicationInfo = packageManager.getApplicationInfo(
                        status.packageName(), PackageManager.ApplicationInfoFlags.of(0));
                entries.add(new Entry(
                        applicationInfo,
                        applicationInfo.loadLabel(packageManager).toString(),
                        status));
            } catch (final PackageManager.NameNotFoundException | RuntimeException ignored) {
                entries.add(new Entry(
                        null,
                        context.getString(R.string.unknown_app),
                        status));
            }
        }

        final Collator collator = Collator.getInstance(
                context.getResources().getConfiguration().getLocales().get(0));
        entries.sort((left, right) -> {
            final int fcmOrder = Boolean.compare(
                    right.status.supportsFcm(), left.status.supportsFcm());
            if (fcmOrder != 0) {
                return fcmOrder;
            }
            final int nameOrder = collator.compare(left.title, right.title);
            return nameOrder != 0 ? nameOrder
                    : left.status.packageName().compareTo(right.status.packageName());
        });
        return entries;
    }

    interface Listener {
        void onLoadingChanged(boolean loading);

        void onEntriesChanged(@NonNull List<Entry> entries);

        void onLoadFailed(@Nullable String message);

        void onIconLoaded(@NonNull Entry entry, @NonNull Drawable icon);
    }

    public static final class Entry {
        @Nullable
        private final ApplicationInfo applicationInfo;
        @NonNull
        private final String title;
        @NonNull
        private final AppStatus status;
        @Nullable
        private Drawable icon;
        private boolean iconRequested;
        private int generation;

        private Entry(
                @Nullable final ApplicationInfo applicationInfo,
                @NonNull final String title,
                @NonNull final AppStatus status) {
            this.applicationInfo = applicationInfo;
            this.title = title;
            this.status = status;
        }

        @NonNull
        String title() {
            return title;
        }

        @NonNull
        AppStatus status() {
            return status;
        }
    }
}
