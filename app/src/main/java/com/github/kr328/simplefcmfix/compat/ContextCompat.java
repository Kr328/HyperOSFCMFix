package com.github.kr328.simplefcmfix.compat;

import android.content.AttributionSource;
import android.content.Context;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ContextCompat {
    private ContextCompat() {
    }

    public static void fixContextImplOpPackage(@NonNull final Context context) throws ReflectiveOperationException {
        final Class<?> contextImpl = context.getClass();
        assert contextImpl.getName().equals("android.app.ContextImpl");

        final String packageName = context.getPackageName();

        final Field attributionSourceField = contextImpl.getDeclaredField("mAttributionSource");
        attributionSourceField.setAccessible(true);
        final AttributionSource attributionSource = (AttributionSource) attributionSourceField.get(context);

        final Method withPackageNameMethod = AttributionSource.class.getMethod("withPackageName", String.class);
        final AttributionSource newAttributionSource = (AttributionSource) withPackageNameMethod.invoke(attributionSource, packageName);
        attributionSourceField.set(context, newAttributionSource);

        final Field opPackageField = contextImpl.getDeclaredField("mOpPackageName");
        opPackageField.setAccessible(true);
        opPackageField.set(context, packageName);
    }
}
