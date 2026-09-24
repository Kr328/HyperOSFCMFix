package android.app;

import android.app.IUidObserver;

interface IActivityManager {
    void registerUidObserver(in IUidObserver observer, int which, int cutpoint, in String callingPackage);
    void unregisterUidObserver(in IUidObserver observer);
}
