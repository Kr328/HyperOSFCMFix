package com.github.kr328.simplefcmfix;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

public class MilletObserver {
    private static final long DEBOUND_DURATION = 1000;
    @NonNull
    private final OnMilletChangedCallback callback;
    @NonNull
    private final Handler handler;
    @Nullable
    private Logcat logcat;

    public MilletObserver(@NonNull final OnMilletChangedCallback callback) {
        this.callback = callback;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void start() throws IOException {
        if (logcat != null) {
            logcat.close();
            logcat = null;
        }

        final Logcat newLogcat = new Logcat(null, "PowerSaveConfigureManager");
        new Thread(() -> {
            try {
                while (true) {
                    final String line = newLogcat.readLine();
                    if (line == null) {
                        break;
                    }

                    handler.removeMessages(0);
                    handler.postDelayed(callback::onChanged, DEBOUND_DURATION);
                }
            } catch (final IOException e) {
                Log.d("MilletObserver", "IOException", e);
            }
        }).start();
    }

    public void stop() throws IOException {
        if (logcat != null) {
            logcat.close();
            logcat = null;
        }
    }

    public interface OnMilletChangedCallback {
        void onChanged();
    }
}
