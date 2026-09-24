package com.github.kr328.simplefcmfix.compat;

import android.annotation.SuppressLint;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.kr328.simplefcmfix.BuildConfig;

import java.util.Objects;

public final class CompatHelper extends ContentProvider {
    private static final String TAG = "CompatHelper";

    private static final String METHOD_WRAP_BINDER = "wrapBinder";
    private static final String KEY_TARGET = "target";
    private static final String KEY_WRAPPER = "wrapper";
    @SuppressLint("StaticFieldLeak")
    @Nullable
    private static Context context = null;

    public static void attachContext(@NonNull final Context context) {
        CompatHelper.context = context;
    }

    public static IBinder wrapBinder(@NonNull final IBinder origin) {
        final Context context = CompatHelper.context;
        if (context == null) {
            return origin;
        }

        final Bundle wrapExtra = new Bundle();
        wrapExtra.putBinder(KEY_TARGET, origin);
        final Bundle reply = context.getContentResolver().call(BuildConfig.APPLICATION_ID + ".helper", METHOD_WRAP_BINDER, null, wrapExtra);
        Objects.requireNonNull(reply, "CompatHelper.wrapBinder[reply]");
        return reply.getBinder(KEY_WRAPPER);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(@NonNull final String method, @Nullable final String arg, @Nullable final Bundle extras) {
        if (!METHOD_WRAP_BINDER.equals(method)) {
            throw new UnsupportedOperationException("Unsupported method: " + method);
        }

        if (extras == null) {
            throw new IllegalArgumentException("call(method = wrapBinder, extras = null)");
        }

        final IBinder target = extras.getBinder(KEY_TARGET);
        if (target == null) {
            throw new IllegalArgumentException("call(method = wrapBinder, extras.target = null)");
        }

        Log.d(TAG, "wrapBinder: " + target);

        final Bundle reply = new Bundle();
        reply.putBinder(KEY_WRAPPER, new BinderProxyWrapper(target));
        return reply;
    }

    @Nullable
    @Override
    public Cursor query(
            @NonNull final Uri uri,
            @Nullable final String[] projection,
            @Nullable final String selection,
            @Nullable final String[] selectionArgs,
            @Nullable final String sortOrder
    ) {
        throw new UnsupportedOperationException("query() is not supported");
    }

    @Nullable
    @Override
    public Uri insert(@NonNull final Uri uri, @Nullable final ContentValues values) {
        throw new UnsupportedOperationException("insert() is not supported");
    }

    @Override
    public int delete(
            @NonNull final Uri uri,
            @Nullable final String selection,
            @Nullable final String[] selectionArgs
    ) {
        throw new UnsupportedOperationException("delete() is not supported");
    }

    @Override
    public int update(
            @NonNull final Uri uri,
            @Nullable final ContentValues values,
            @Nullable final String selection,
            @Nullable final String[] selectionArgs
    ) {
        throw new UnsupportedOperationException("update() is not supported");
    }

    @Nullable
    @Override
    public String getType(@NonNull final Uri uri) {
        throw new UnsupportedOperationException("getType() is not supported");
    }

    private static final class BinderProxyWrapper extends Binder {
        @NonNull
        private final IBinder target;

        BinderProxyWrapper(@NonNull final IBinder target) {
            this.target = target;
        }

        @Override
        protected boolean onTransact(
                final int code,
                @NonNull final Parcel data,
                @Nullable final Parcel reply,
                final int flags
        ) throws RemoteException {
            // Forward the raw parcel untouched: the interface token is still unread and binder
            // objects inside (e.g. observer transports) pass through unchanged.
            return target.transact(code, data, reply, flags);
        }
    }
}
