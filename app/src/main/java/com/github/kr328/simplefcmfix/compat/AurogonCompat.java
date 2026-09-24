package com.github.kr328.simplefcmfix.compat;

import android.content.Context;
import android.database.ContentObserver;
import android.provider.Settings;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

public final class AurogonCompat {
    @NonNull
    private static final String AUROGON_ENABLE_KEY = "aurogon_enable";
    @NonNull
    private static final String BROADCAST_CTRL_PREFIX = "broadcastctrl:";

    private AurogonCompat() {
    }

    @NonNull
    public static AurogonEnable getAurogonEnable(@NonNull final Context context) {
        return parseAurogonEnable(
                Settings.Global.getString(context.getContentResolver(), AUROGON_ENABLE_KEY)
        );
    }

    public static void setAurogonEnable(
            @NonNull final Context context,
            @NonNull final AurogonEnable value
    ) {
        Settings.Global.putString(
                context.getContentResolver(),
                AUROGON_ENABLE_KEY,
                serializeAurogonEnable(value)
        );
    }

    public static void observeAurogonEnable(
            @NonNull final Context context,
            @NonNull final ContentObserver observer
    ) {
        context.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(AUROGON_ENABLE_KEY),
                false,
                observer
        );
    }

    @NonNull
    private static AurogonEnable parseAurogonEnable(@Nullable final String value) {
        if (value == null || value.isEmpty()) {
            return new AurogonEnable(null, List.of());
        }

        final LinkedHashSet<Pair<String, String>> excludes = new LinkedHashSet<>();
        final List<String> otherFields = new ArrayList<>();
        boolean hasBroadcastCtrl = false;
        boolean enabled = false;

        for (final String field : value.split(";", -1)) {
            if (field.isEmpty()) {
                continue;
            }

            if (!field.startsWith(BROADCAST_CTRL_PREFIX)) {
                otherFields.add(field);
                continue;
            }

            final String[] parts = field.split("#", -1);
            if (!parts[0].equals(BROADCAST_CTRL_PREFIX + "true")
                    && !parts[0].equals(BROADCAST_CTRL_PREFIX + "false")) {
                continue;
            }

            hasBroadcastCtrl = true;
            enabled = parts[0].equals(BROADCAST_CTRL_PREFIX + "true");

            for (int i = 1; i < parts.length; i++) {
                final String[] rule = parts[i].split("/", -1);
                if (rule.length != 2 || rule[0].isEmpty() || rule[1].isEmpty()) {
                    continue;
                }
                excludes.add(Pair.create(rule[0], rule[1]));
            }
        }

        final BroadcastCtrl broadcastCtrl = hasBroadcastCtrl
                ? new BroadcastCtrl(enabled, excludes)
                : null;
        return new AurogonEnable(broadcastCtrl, otherFields);
    }

    @NonNull
    static String serializeAurogonEnable(@NonNull final AurogonEnable value) {
        Objects.requireNonNull(value, "value");

        final StringJoiner fields = new StringJoiner(";");
        final BroadcastCtrl broadcastCtrl = value.broadcastCtrl();
        if (broadcastCtrl != null) {
            final StringBuilder field = new StringBuilder(BROADCAST_CTRL_PREFIX)
                    .append(broadcastCtrl.enabled());
            for (final Pair<String, String> exclude : broadcastCtrl.excludes()) {
                field.append('#')
                        .append(exclude.first)
                        .append('/')
                        .append(exclude.second);
            }
            fields.add(field);
        }
        value.otherFields().forEach(fields::add);
        return fields.toString();
    }

    public record BroadcastCtrl(
            boolean enabled,
            @NonNull LinkedHashSet<Pair<String, String>> excludes
    ) {
    }

    public record AurogonEnable(
            @Nullable BroadcastCtrl broadcastCtrl,
            @NonNull List<String> otherFields
    ) {
    }
}
