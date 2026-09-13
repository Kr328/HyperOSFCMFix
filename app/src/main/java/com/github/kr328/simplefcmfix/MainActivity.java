package com.github.kr328.simplefcmfix;

import android.Manifest;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * @noinspection deprecation
 */
@SuppressWarnings("deprecation")
public final class MainActivity extends Activity {
    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setElevation(0);
        }

        if (!isSupportedSystem()) {
            final TextView message = new TextView(this);
            message.setGravity(Gravity.CENTER);
            message.setText(R.string.unsupported_system);
            message.setPaddingRelative(40, 40, 40, 40);
            message.setTypeface(message.getTypeface(), Typeface.BOLD);

            setContentView(message);

            return;
        }

        setContentView(R.layout.main);

        if (savedInstanceState == null) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
            }

            getFragmentManager()
                    .beginTransaction()
                    .replace(R.id.preferences, new MainFragment())
                    .commit();
        }
    }

    private static boolean isSupportedSystem() {
        try {
            return "CN".equals(SystemProperties.get("ro.vendor.miui.region"))
                    && !TextUtils.isEmpty(SystemProperties.get("ro.mi.os.version.name"));
        } catch (final RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public static final class MainFragment extends PreferenceFragment
            implements ShizukuHelper.OnStateChangedListener {
        @Nullable
        private ShizukuHelper shizukuHelper;

        @Override
        public void onCreate(@Nullable final Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            addPreferencesFromResource(R.xml.preference_main);

            findPreference("shizuku_state").setOnPreferenceClickListener(preference -> {
                final ShizukuHelper.State state = Objects.requireNonNull(shizukuHelper).getState();
                if (state instanceof ShizukuHelper.State.NoPermission) {
                    shizukuHelper.requestPermission();
                } else if (state instanceof ShizukuHelper.State.StartFailed) {
                    shizukuHelper.startUserService();
                } else {
                    shizukuHelper.startShizukuApp();
                }

                return true;
            });

            final SwitchPreference serviceEnabled =
                    (SwitchPreference) findPreference("service_enabled");
            serviceEnabled.setEnabled(false);
            serviceEnabled.setOnPreferenceChangeListener((preference, newValue) -> {
                final ShizukuHelper.State state =
                        Objects.requireNonNull(shizukuHelper).getState();
                if (state instanceof final ShizukuHelper.State.Ready ready) {
                    try {
                        if ((boolean) newValue) {
                            ready.remote.start();
                        } else {
                            ready.remote.stop();
                        }
                    } catch (final RemoteException | RuntimeException e) {
                        Toast.makeText(getActivity(), e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }

                updateServiceEnabledPreference();

                return false;
            });

            final Preference batteryOptimizationAllowlist =
                    findPreference("battery_optimization_allowlist");
            batteryOptimizationAllowlist.setEnabled(false);
            batteryOptimizationAllowlist.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(getActivity(),
                        AllowlistActivity.class));

                return true;
            });

            final Preference history = findPreference("history");
            history.setEnabled(false);
            history.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(getActivity(), HistoryActivity.class));

                return true;
            });
        }

        @Override
        public void onResume() {
            super.onResume();

            if (shizukuHelper != null) {
                shizukuHelper.startUserService();
            }
        }

        @Override
        public void onAttach(@NonNull final Context context) {
            super.onAttach(context);

            shizukuHelper = new ShizukuHelper(context);
            shizukuHelper.addOnStateChangedListener(this);
        }

        @Override
        public void onDetach() {
            Objects.requireNonNull(shizukuHelper).removeOnStateChangedListener(this);
            shizukuHelper.close();
            shizukuHelper = null;

            super.onDetach();
        }

        @Override
        public void onViewCreated(
                @NonNull final View view,
                @Nullable final Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);

            final ListView list = view.findViewById(android.R.id.list);
            if (list != null) {
                list.setDivider(null);
            }

            onShizukuStateChanged();
        }

        @Override
        public void onShizukuStateChanged() {
            if (!isAdded()) {
                return;
            }

            final Preference preference = findPreference("shizuku_state");
            final ShizukuHelper.State state = Objects.requireNonNull(shizukuHelper).getState();

            if (state instanceof ShizukuHelper.State.Unavailable) {
                preference.setSummary(R.string.shizuku_state_unavailable);
            } else if (state instanceof ShizukuHelper.State.NoPermission) {
                preference.setSummary(R.string.shizuku_state_no_permission);
            } else if (state instanceof ShizukuHelper.State.Starting) {
                preference.setSummary(R.string.shizuku_state_starting);
            } else if (state instanceof ShizukuHelper.State.StartFailed) {
                preference.setSummary(R.string.shizuku_state_start_failed);
            } else if (state instanceof ShizukuHelper.State.Ready) {
                preference.setSummary(R.string.shizuku_state_ready);
            }

            findPreference("battery_optimization_allowlist")
                    .setEnabled(state instanceof ShizukuHelper.State.Ready);
            findPreference("history")
                    .setEnabled(state instanceof ShizukuHelper.State.Ready);
            updateServiceEnabledPreference();
        }

        private void updateServiceEnabledPreference() {
            final SwitchPreference preference =
                    (SwitchPreference) findPreference("service_enabled");
            final ShizukuHelper.State state = Objects.requireNonNull(shizukuHelper).getState();

            preference.setEnabled(false);
            preference.setChecked(false);

            if (state instanceof final ShizukuHelper.State.Ready ready) {
                try {
                    preference.setChecked(ready.remote.isRunning());
                    preference.setEnabled(true);
                } catch (final RemoteException | RuntimeException ignored) {
                }
            }
        }
    }
}
