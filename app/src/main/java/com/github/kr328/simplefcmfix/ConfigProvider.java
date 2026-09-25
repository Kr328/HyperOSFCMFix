package com.github.kr328.simplefcmfix;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

public final class ConfigProvider extends ContentProvider {
    private static final String APPLIER_CONFIG_KEY = "applier_config";

    private static final String AUTO_ALLOW_FCM_WAKE_FOR_PLAY_STORE_APPS = "auto_allow_fcm_wake_for_play_store_apps";

    private SharedPreferences applierConfig;

    public static ApplierConfig getApplierConfig(final Context context) {
        final Bundle ret = context.getContentResolver().call(BuildConfig.APPLICATION_ID + ".config", "get", APPLIER_CONFIG_KEY, null);
        if (ret == null) {
            throw new IllegalArgumentException("null ret");
        }
        ret.setClassLoader(ApplierConfig.class.getClassLoader());
        return ret.getParcelable("data", ApplierConfig.class);
    }

    public static void setApplierConfig(final Context context, final ApplierConfig cfg) {
        final Bundle extras = new Bundle();
        extras.putParcelable("data", cfg);
        context.getContentResolver().call(BuildConfig.APPLICATION_ID + ".config", "put", APPLIER_CONFIG_KEY, extras);
    }

    public static void observeApplierConfig(final Context context, final ContentObserver observer) {
        context.getContentResolver().registerContentObserver(Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".config/" + APPLIER_CONFIG_KEY), false, observer);
    }

    @Nullable
    @Override
    public Bundle call(@NonNull final String method, @Nullable final String arg, @Nullable final Bundle extras) {
        switch (method) {
            case "get" -> {
                final Parcelable data;
                switch (Objects.requireNonNullElse(arg, "")) {
                    case APPLIER_CONFIG_KEY ->
                            data = new ApplierConfig(applierConfig.getBoolean(AUTO_ALLOW_FCM_WAKE_FOR_PLAY_STORE_APPS, true));
                    case "" -> throw new IllegalArgumentException("empty key");
                    default -> throw new IllegalArgumentException("unknown key");
                }
                final Bundle ret = new Bundle();
                ret.putParcelable("data", data);
                return ret;
            }
            case "put" -> {
                if (extras == null) {
                    throw new IllegalArgumentException("null extras");
                }

                extras.setClassLoader(ConfigProvider.class.getClassLoader());

                switch (Objects.requireNonNullElse(arg, "")) {
                    case APPLIER_CONFIG_KEY -> {
                        final ApplierConfig cfg = extras.getParcelable("data", ApplierConfig.class);
                        if (cfg == null) {
                            throw new IllegalArgumentException("null data");
                        }

                        final SharedPreferences.Editor editor = applierConfig.edit();
                        editor.putBoolean(AUTO_ALLOW_FCM_WAKE_FOR_PLAY_STORE_APPS, cfg.autoAllowFCMWakeForPlayStoreApps());
                        editor.apply();
                    }
                    case "" -> throw new IllegalArgumentException("empty key");
                    default -> throw new IllegalArgumentException("unknown key");
                }

                final Context context = getContext();
                if (context != null) {
                    context.getContentResolver().notifyChange(Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".config/" + arg), null);
                }
                return null;
            }
            default -> {
                return super.call(method, arg, extras);
            }
        }
    }

    @Override
    public int delete(@NonNull final Uri uri, @Nullable final String selection, @Nullable final String[] selectionArgs) {
        throw new UnsupportedOperationException("delete");
    }

    @Nullable
    @Override
    public String getType(@NonNull final Uri uri) {
        throw new UnsupportedOperationException("getType");
    }

    @Nullable
    @Override
    public Uri insert(@NonNull final Uri uri, @Nullable final ContentValues values) {
        throw new UnsupportedOperationException("insert");
    }

    @Override
    public boolean onCreate() {
        applierConfig = Objects.requireNonNull(getContext()).getSharedPreferences(APPLIER_CONFIG_KEY, 0);

        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull final Uri uri, @Nullable final String[] projection, @Nullable final String selection, @Nullable final String[] selectionArgs, @Nullable final String sortOrder) {
        throw new UnsupportedOperationException("query");
    }

    @Override
    public int update(@NonNull final Uri uri, @Nullable final ContentValues values, @Nullable final String selection, @Nullable final String[] selectionArgs) {
        throw new UnsupportedOperationException("update");
    }
}
