package com.github.kr328.simplefcmfix;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.AttributionSource;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentService;
import android.os.Bundle;
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

    private static Object createContentServiceDelegate(@NonNull final Context context, @NonNull final IContentService origin) {
        return Proxy.newProxyInstance(
                ContentResolver.class.getClassLoader(),
                new Class[]{IContentService.class},
                (_proxy, method, args) -> {
                    try {
                        return switch (method.getName()) {
                            case "registerContentObserver", "unregisterContentObserver" -> {
                                try {
                                    final Bundle wrapExtra = new Bundle();
                                    wrapExtra.putBinder("target", origin.asBinder());
                                    final Bundle reply = context.getContentResolver().call(BuildConfig.APPLICATION_ID + ".proxy", "wrapBinder", null, wrapExtra);
                                    Objects.requireNonNull(reply, "ProxyProvider.wrapBinder[reply]");
                                    final IBinder wrapper = reply.getBinder("wrapper");

                                    final IContentService proxy = IContentService.Stub.asInterface(wrapper);

                                    yield method.invoke(proxy, args);
                                } catch (final Exception e) {
                                    Log.e(TAG, "registerContentObserver/unregisterContentObserver", e);

                                    yield method.invoke(origin, args);
                                }
                            }
                            default -> method.invoke(origin, args);
                        };
                    } catch (final InvocationTargetException e) {
                        throw e.getTargetException();
                    } catch (final Exception e) {
                        return method.invoke(origin, args);
                    }
                }
        );
    }

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

    public static void applyActivityManagerCompatForShizuku() throws ReflectiveOperationException {
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

    public static void applyContentServiceCompatForShizuku(@NonNull final Context context) throws ReflectiveOperationException {
        final Field contentResolverField = ContentResolver.class.getDeclaredField("sContentService");
        contentResolverField.setAccessible(true);

        final Method getContentServiceMethod = ContentResolver.class.getMethod("getContentService");
        final IContentService origin = (IContentService) getContentServiceMethod.invoke(null);
        contentResolverField.set(
                null,
                createContentServiceDelegate(context, Objects.requireNonNull(origin, "origin"))
        );
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
