package com.github.kr328.simplefcmfix.compat;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.IUidObserver;
import android.os.Process;
import android.os.ServiceManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.github.kr328.simplefcmfix.refine.Refine;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Objects;

@SuppressWarnings("JavaReflectionMemberAccess")
@SuppressLint("DiscouragedPrivateApi")
@Refine
public class ActivityCompat {
    private static final String TAG = "ActivityManagerCompat";

    private static final IActivityManager activityManager = IActivityManager.Stub.asInterface(ServiceManager.getService("activity"));

    private static int UID_OBSERVER_GONE = 1 << 1;
    private static int UID_OBSERVER_ACTIVE = 1 << 3;

    static {
        try {
            UID_OBSERVER_GONE = getUidObserverGone();
            UID_OBSERVER_ACTIVE = getUidObserverActive();
        } catch (final Throwable e) {
            Log.e(TAG, "Failed to get UID_OBSERVER_GONE or UID_OBSERVER_ACTIVE", e);
        }
    }

    @Refine.GetStatic(value = ActivityManager.class, name = "UID_OBSERVER_GONE")
    private static int getUidObserverGone() {
        throw new IllegalArgumentException("Stub!");
    }

    @Refine.GetStatic(value = ActivityManager.class, name = "UID_OBSERVER_ACTIVE")
    private static int getUidObserverActive() {
        throw new IllegalArgumentException("Stub!");
    }

    @NonNull
    private static Object createActivityServiceDelegate(@NonNull final IActivityManager origin) {
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
                                    yield origin.getContentProviderExternal(name, userId, null, name);
                                }

                                yield method.invoke(origin, actualArgs);
                            }
                            case "refContentProvider" -> true;
                            case "removeContentProvider" -> null;
                            default -> method.invoke(origin, actualArgs);
                        };
                    } catch (final InvocationTargetException e) {
                        throw e.getTargetException();
                    } catch (final Throwable e) {
                        Log.w(TAG, "Proxy getContentProviderExternal failed", e);

                        return method.invoke(origin, actualArgs);
                    }
                }
        );
    }

    public static void installDelegate() throws ReflectiveOperationException {
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
                createActivityServiceDelegate((IActivityManager) activityManager)
        );
    }

    public static void registerUidObserver(@NonNull final IUidObserverCompat observer) throws Throwable {
        activityManager.registerUidObserver(observer,
                UID_OBSERVER_GONE | UID_OBSERVER_ACTIVE,
                0,
                android.os.Process.myUid() == Process.SHELL_UID ? "com.android.shell" : "android"
        );
    }

    public static void unregisterUidObserver(@NonNull final IUidObserverCompat observer) throws Throwable {
        activityManager.unregisterUidObserver(observer);
    }

    public static abstract class IUidObserverCompat extends IUidObserver.Stub {
        @Override
        public void onUidIdle(final int uid, final boolean disabled) {
        }

        @Override
        public void onUidStateChanged(final int uid, final int procState, final long procStateSeq, final int capability) {
        }

        @Override
        public void onUidProcAdjChanged(final int uid, final int adj) {
        }

        @Override
        public void onUidCachedChanged(final int uid, final boolean cached) {
        }
    }
}
