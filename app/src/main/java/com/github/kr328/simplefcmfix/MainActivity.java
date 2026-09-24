package com.github.kr328.simplefcmfix;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.kr328.simplefcmfix.compat.MilletCompat;

import java.util.Objects;

/**
 * @noinspection deprecation
 */
@SuppressWarnings("deprecation")
public final class MainActivity extends Activity {
    private static boolean isSupportedSystem() {
        try {
            return "CN".equals(SystemProperties.get("ro.vendor.miui.region"))
                    && !TextUtils.isEmpty(SystemProperties.get("ro.mi.os.version.name"));
        } catch (final RuntimeException | LinkageError ignored) {
            return false;
        }
    }

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

    public static final class MainFragment extends PreferenceFragment
            implements ShizukuHelper.OnStateChangedListener {
        @Nullable
        private ShizukuHelper shizukuHelper;
        @Nullable
        private Boolean lastInBatteryOptimizationAllowlist;

        @SuppressLint("BatteryLife")
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

            final SwitchPreference autoAllowFCMWakeForPlayStoreApps =
                    (SwitchPreference) findPreference("auto_allow_fcm_wake_for_play_store_apps");
            autoAllowFCMWakeForPlayStoreApps.setOnPreferenceChangeListener((preference, newValue) -> {
                try {
                    final ApplierConfig config = ConfigProvider.getApplierConfig(getActivity());
                    config.autoAllowFCMWakeForPlayStoreApps = (boolean) newValue;
                    ConfigProvider.setApplierConfig(getActivity(), config);
                    autoAllowFCMWakeForPlayStoreApps.setChecked((boolean) newValue);
                } catch (final RuntimeException e) {
                    Toast.makeText(getActivity(), R.string.applier_config_save_failed, Toast.LENGTH_SHORT).show();
                }

                return false;
            });

            findPreference("self_battery_optimization")
                    .setOnPreferenceClickListener(preference -> {
                        final Activity activity = getActivity();
                        try {
                            startActivity(new Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(Uri.fromParts("package", activity.getPackageName(), null)));
                        } catch (final RuntimeException e) {
                            Toast.makeText(
                                    activity, e.getMessage(), Toast.LENGTH_SHORT).show();
                        }

                        return true;
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

            updateSelfBatteryOptimizationPreference();
            updateAutoAllowFCMWakeForPlayStoreAppsPreference();

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

        private void updateSelfBatteryOptimizationPreference() {
            final Context context = getContext();
            if (context == null) {
                return;
            }

            final boolean inAllowlist = MilletCompat.isMilletNoRestrictApp(
                    context, context.getPackageName());
            final int summary = inAllowlist
                    ? R.string.self_battery_optimization_in_allowlist
                    : R.string.self_battery_optimization_not_in_allowlist;

            findPreference("self_battery_optimization").setSummary(summary);

            if (!isResumed()) {
                // Keep the previous state so the change is reported once the fragment resumes,
                // instead of creating a Toast while the app is in the background.
                return;
            }

            if (lastInBatteryOptimizationAllowlist != null
                    && lastInBatteryOptimizationAllowlist != inAllowlist) {
                Toast.makeText(context, summary, Toast.LENGTH_SHORT).show();
            }

            lastInBatteryOptimizationAllowlist = inAllowlist;
        }

        private void updateAutoAllowFCMWakeForPlayStoreAppsPreference() {
            final SwitchPreference preference = (SwitchPreference)
                    findPreference("auto_allow_fcm_wake_for_play_store_apps");
            try {
                final ApplierConfig config = ConfigProvider.getApplierConfig(getActivity());
                preference.setChecked(config.autoAllowFCMWakeForPlayStoreApps);
                preference.setEnabled(true);
            } catch (final RuntimeException e) {
                preference.setEnabled(false);
                Toast.makeText(getActivity(), R.string.applier_config_load_failed, Toast.LENGTH_SHORT).show();
            }
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
