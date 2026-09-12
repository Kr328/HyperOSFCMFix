package com.github.kr328.simplefcmfix;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.AttributionSource;
import android.content.Context;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;

@SuppressWarnings("JavaReflectionMemberAccess")
@SuppressLint({"DiscouragedPrivateApi", "PrivateApi"})
public final class Compat {
    @NonNull
    private static final String TAG = "ContentProviderCompat";

    @NonNull
    private static Object createActivityServiceDelegate(@NonNull final Object origin)
            throws NoSuchMethodException {
        final Method getContentProviderExternal = origin.getClass().getMethod(
                "getContentProviderExternal",
                String.class,
                int.class,
                IBinder.class,
                String.class
        );

        return Proxy.newProxyInstance(
                ActivityManager.class.getClassLoader(),
                new Class[]{IActivityManager.class},
                (proxy, method, args) -> {
                    final Object[] actualArgs = args == null ? new Object[0] : args;

                    try {
                        return switch (method.getName()) {
                            case "getContentProvider" -> {
                                if (actualArgs.length >= 4
                                        && actualArgs[2] instanceof final String name
                                        && actualArgs[3] instanceof final Integer userId) {
                                    yield getContentProviderExternal.invoke(
                                            origin,
                                            name,
                                            userId,
                                            null,
                                            name
                                    );
                                }

                                yield method.invoke(origin, actualArgs);
                            }
                            case "refContentProvider" -> true;
                            case "removeContentProvider" -> null;
                            default -> method.invoke(origin, actualArgs);
                        };
                    } catch (final InvocationTargetException e) {
                        throw e.getTargetException();
                    } catch (final Exception e) {
                        Log.w(TAG, "Proxy getContentProviderExternal failed", e);

                        return method.invoke(origin, actualArgs);
                    }
                }
        );
    }

    public static void applyContentProviderCompatForShizuku() throws ReflectiveOperationException {
        // apply ActivityManager compat
        final Field activityManagerField = ActivityManager.class.getDeclaredField("IActivityManagerSingleton");
        activityManagerField.setAccessible(true);

        final Object activityManagerSingleton = Objects.requireNonNull(
                activityManagerField.get(null));
        final Object activityManager = Objects.requireNonNull(
                activityManagerSingleton.getClass()
                        .getMethod("get")
                        .invoke(activityManagerSingleton));

        final Class<?> singletonClass = Objects.requireNonNull(
                activityManagerSingleton.getClass().getSuperclass());
        final Field instanceField = singletonClass.getDeclaredField("mInstance");
        instanceField.setAccessible(true);
        instanceField.set(
                activityManagerSingleton,
                createActivityServiceDelegate(activityManager)
        );
    }

    public static void applyNewPackageNameToContextImpl(@NonNull final Context context, @NonNull final String newPackageName) throws ReflectiveOperationException {
        final Field attributionSourceField = context.getClass().getDeclaredField("mAttributionSource");
        attributionSourceField.setAccessible(true);
        final AttributionSource attributionSource = (AttributionSource) attributionSourceField.get(context);

        final Method withPackageNameMethod = AttributionSource.class.getMethod("withPackageName", String.class);
        final AttributionSource newAttributionSource = (AttributionSource) withPackageNameMethod.invoke(attributionSource, newPackageName);
        attributionSourceField.set(context, newAttributionSource);
    }
}
