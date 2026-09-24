package com.github.kr328.simplefcmfix.compat;

import android.content.Context;
import android.database.ContentObserver;
import android.provider.Settings;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class MilletCompat {
    @NonNull
    private static final String MILLET_NO_RESTRICT_APP_KEY = "MILLET_NO_RESTRICT_APP";

    @NonNull
    public static List<String> getMilletNoRestrictApps(
            @NonNull final Context context
    ) {
        final String value = Objects.requireNonNullElse(Settings.System.getString(context.getContentResolver(), MILLET_NO_RESTRICT_APP_KEY), "");
        return Arrays.stream(value.split(",")).map(String::trim).collect(Collectors.toList());
    }

    public static boolean isMilletNoRestrictApp(
            @NonNull final Context context,
            @NonNull final String packageName
    ) {
        final String value = Objects.requireNonNullElse(Settings.System.getString(context.getContentResolver(), MILLET_NO_RESTRICT_APP_KEY), "");
        return Arrays.stream(value.split(",")).map(String::trim).anyMatch(packageName::equals);
    }

    public static void setMilletNoRestrictApps(
            @NonNull final Context context,
            @NonNull final List<String> apps
    ) {
        Settings.System.putString(context.getContentResolver(), MILLET_NO_RESTRICT_APP_KEY, String.join(", ", apps));
    }

    public static void observeMilletNoRestrictApps(
            @NonNull final Context context,
            @NonNull final ContentObserver observer
    ) {
        context.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(MILLET_NO_RESTRICT_APP_KEY),
                false,
                observer
        );
    }
}
