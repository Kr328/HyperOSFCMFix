package com.github.kr328.simplefcmfix;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HistoryActivity extends Activity
        implements ShizukuHelper.OnStateChangedListener {
    @NonNull
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    @Nullable
    private ShizukuHelper shizukuHelper;
    @Nullable
    private HistoryAdapter adapter;
    private int loadGeneration;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.history);

        final ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setElevation(0);
        }

        final ListView list = findViewById(android.R.id.list);
        list.setDivider(null);
        adapter = new HistoryAdapter(this);
        list.setAdapter(adapter);

        shizukuHelper = new ShizukuHelper(this);
        shizukuHelper.addOnStateChangedListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        updateHistory();
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
                updateHistory();
            }
        });
    }

    private void updateHistory() {
        final ShizukuHelper helper = shizukuHelper;
        final HistoryAdapter currentAdapter = adapter;
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
                final List<HistoryRecord> records = reverse(ready.remote.getHistory());
                runOnUiThread(() -> {
                    if (!isDestroyed() && generation == loadGeneration) {
                        Objects.requireNonNull(adapter).setItems(records);
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
    private static List<HistoryRecord> reverse(@Nullable final HistoryRecord[] records) {
        if (records == null) {
            return List.of();
        }

        final ArrayList<HistoryRecord> result = new ArrayList<>(records.length);
        for (int i = records.length - 1; i >= 0; --i) {
            result.add(records[i]);
        }
        return result;
    }

    private static final class HistoryAdapter extends BaseAdapter {
        @NonNull
        private final Context context;
        @NonNull
        private final LayoutInflater inflater;
        @NonNull
        private List<HistoryRecord> items = List.of();

        private HistoryAdapter(@NonNull final Context context) {
            this.context = context;
            inflater = LayoutInflater.from(context);
        }

        private void setItems(@NonNull final List<HistoryRecord> items) {
            this.items = List.copyOf(items);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public HistoryRecord getItem(final int position) {
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
                view = inflater.inflate(R.layout.item_history, parent, false);
                holder = new ViewHolder(
                        view.findViewById(R.id.title),
                        view.findViewById(R.id.subtitle));
                view.setTag(holder);
            } else {
                view = convertView;
                holder = (ViewHolder) view.getTag();
            }

            final HistoryRecord item = getItem(position);
            holder.title.setText(context.getString(
                    R.string.history_item_title,
                    context.getString(actionTitle(item.action())),
                    context.getString(causeTitle(item.cause()))));
            holder.subtitle.setText(DateUtils.formatDateTime(
                    context,
                    item.timestamp(),
                    DateUtils.FORMAT_SHOW_DATE
                            | DateUtils.FORMAT_SHOW_TIME
                            | DateUtils.FORMAT_SHOW_YEAR));

            return view;
        }

        private int actionTitle(@NonNull final HistoryRecord.Action action) {
            return switch (action) {
                case INJECT -> R.string.history_action_inject;
                case REMOVE -> R.string.history_action_remove;
                case RECONNECT -> R.string.history_action_reconnect;
            };
        }

        private int causeTitle(@NonNull final HistoryRecord.Cause cause) {
            return switch (cause) {
                case MANUAL -> R.string.history_cause_manual;
                case EVENT -> R.string.history_cause_event;
                case WATCHDOG -> R.string.history_cause_watchdog;
            };
        }
    }

    private record ViewHolder(
            @NonNull TextView title,
            @NonNull TextView subtitle) {
    }
}
