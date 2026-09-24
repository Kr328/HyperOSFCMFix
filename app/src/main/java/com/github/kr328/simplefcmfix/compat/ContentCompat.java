package com.github.kr328.simplefcmfix.compat;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.IContentService;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;

@SuppressWarnings("JavaReflectionMemberAccess")
@SuppressLint({"DiscouragedPrivateApi", "PrivateApi"})
public final class ContentCompat {
    @NonNull
    private static final String TAG = "ContentProviderCompat";

    private ContentCompat() {
    }

    private static Object createContentServiceDelegate(@NonNull final IContentService origin) {
        return Proxy.newProxyInstance(
                ContentResolver.class.getClassLoader(),
                new Class[]{IContentService.class},
                (_proxy, method, args) -> {
                    try {
                        return switch (method.getName()) {
                            case "registerContentObserver", "unregisterContentObserver" -> {
                                try {
                                    final IContentService proxy = IContentService.Stub.asInterface(CompatHelper.wrapBinder(origin.asBinder()));

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

    public static void apply() throws ReflectiveOperationException {
        final Field contentResolverField = ContentResolver.class.getDeclaredField("sContentService");
        contentResolverField.setAccessible(true);

        final Method getContentServiceMethod = ContentResolver.class.getMethod("getContentService");
        final IContentService origin = (IContentService) getContentServiceMethod.invoke(null);
        contentResolverField.set(
                null,
                createContentServiceDelegate(Objects.requireNonNull(origin, "origin"))
        );
    }
}
