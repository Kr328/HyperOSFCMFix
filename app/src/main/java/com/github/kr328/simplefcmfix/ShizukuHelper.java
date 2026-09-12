package com.github.kr328.simplefcmfix;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import rikka.shizuku.Shizuku;

public final class ShizukuHelper implements ServiceConnection, Shizuku.OnBinderDeadListener, Shizuku.OnBinderReceivedListener, Shizuku.OnRequestPermissionResultListener, AutoCloseable {
    private static final int REQUEST_PERMISSION = 0;

    @NonNull
    private final Context context;
    @NonNull
    private final Handler handler = new Handler(Looper.getMainLooper());
    @NonNull
    private final CopyOnWriteArrayList<OnStateChangedListener> listeners = new CopyOnWriteArrayList<>();
    @NonNull
    private final Shizuku.UserServiceArgs userServiceArgs;

    @NonNull
    private State state = new State.Unavailable();
    private boolean closed;

    public ShizukuHelper(@NonNull final Context context) {
        this.context = context;
        this.userServiceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(context, ShizukuRemote.class))
                .daemon(true)
                .debuggable(false)
                .processNameSuffix("shizuku");

        Shizuku.addBinderDeadListener(this);
        Shizuku.addBinderReceivedListenerSticky(this);
        Shizuku.addRequestPermissionResultListener(this);

        rebindService();
    }

    @NonNull
    public State getState() {
        return state;
    }

    private void setState(@NonNull final State state) {
        this.state = state;
        for (final OnStateChangedListener listener : listeners) {
            listener.onShizukuStateChanged();
        }
    }

    public void addOnStateChangedListener(@NonNull final OnStateChangedListener listener) {
        listeners.add(listener);
    }

    public void removeOnStateChangedListener(@NonNull final OnStateChangedListener listener) {
        listeners.remove(listener);
    }

    public void requestPermission() {
        Shizuku.requestPermission(REQUEST_PERMISSION);

        rebindService();
    }

    public void startShizukuApp() {
        try {
            final Intent launchIntent = context.getPackageManager()
                    .getLaunchIntentForPackage("moe.shizuku.privileged.api");
            context.startActivity(Objects.requireNonNullElseGet(
                    launchIntent,
                    () -> new Intent(Intent.ACTION_VIEW).setData(Uri.parse("https://shizuku.rikka.app/")))
            );
        } catch (final RuntimeException ignored) {
        }
    }

    public void startUserService() {
        rebindService();
    }

    private void rebindService() {
        handler.post(() -> {
            if (closed) {
                return;
            }

            if (state instanceof final State.Ready ready
                    && ready.remote.asBinder().pingBinder()) {
                return;
            }

            if (!Shizuku.pingBinder()) {
                setState(new State.Unavailable());
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                setState(new State.NoPermission());
            } else {
                setState(new State.Starting());
                try {
                    Shizuku.bindUserService(userServiceArgs, this);
                } catch (final RuntimeException ignored) {
                    setState(new State.StartFailed());
                }
            }
        });
    }

    @Override
    public void onServiceConnected(
            @NonNull final ComponentName name,
            @NonNull final IBinder service) {
        if (!closed) {
            final IShizukuRemote remote = IShizukuRemote.Stub.asInterface(service);

            setState(new State.Ready(remote));
        }
    }

    @Override
    public void onServiceDisconnected(@NonNull final ComponentName name) {
        if (!closed) {
            setState(new State.StartFailed());
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        Shizuku.removeBinderDeadListener(this);
        Shizuku.removeBinderReceivedListener(this);
        Shizuku.removeRequestPermissionResultListener(this);

        try {
            Shizuku.unbindUserService(userServiceArgs, this, false);
        } catch (final RuntimeException ignored) {
        }
    }

    @Override
    public void onBinderDead() {
        rebindService();
    }

    @Override
    public void onBinderReceived() {
        rebindService();
    }

    @Override
    public void onRequestPermissionResult(final int requestCode, final int grantResult) {
        if (requestCode == REQUEST_PERMISSION) {
            rebindService();
        }
    }

    public interface OnStateChangedListener {
        void onShizukuStateChanged();
    }

    public sealed static class State permits State.Unavailable, State.NoPermission,
            State.Starting, State.StartFailed, State.Ready {
        public static final class Unavailable extends State {
        }

        public static final class NoPermission extends State {
        }

        public static final class Starting extends State {
        }

        public static final class StartFailed extends State {
        }

        public static final class Ready extends State {
            @NonNull
            public final IShizukuRemote remote;

            public Ready(@NonNull final IShizukuRemote remote) {
                this.remote = remote;
            }
        }
    }
}
