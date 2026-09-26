package com.github.kr328.simplefcmfix.compat;

import android.content.AttributionSource;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.kr328.simplefcmfix.refine.Refine;

import java.lang.reflect.Field;
import java.util.Objects;

@Refine
public final class ContextCompat {
    private ContextCompat() {
    }

    @Refine.InvokeVirtual
    private static AttributionSource withPackageName(@NonNull final AttributionSource source, @Nullable final String packageName) {
        throw new IllegalArgumentException("Stub!");
    }

    public static void fixContextImplOpPackage(@NonNull final Context context) throws Throwable {
        final Class<?> contextImpl = context.getClass();
        assert contextImpl.getName().equals("android.app.ContextImpl");

        final String packageName = context.getPackageName();

        final Field attributionSourceField = contextImpl.getDeclaredField("mAttributionSource");
        attributionSourceField.setAccessible(true);
        final AttributionSource attributionSource = (AttributionSource) attributionSourceField.get(context);

        final AttributionSource newAttributionSource = withPackageName(Objects.requireNonNull(attributionSource), packageName);
        attributionSourceField.set(context, newAttributionSource);

        final Field opPackageField = contextImpl.getDeclaredField("mOpPackageName");
        opPackageField.setAccessible(true);
        opPackageField.set(context, packageName);
    }
}
