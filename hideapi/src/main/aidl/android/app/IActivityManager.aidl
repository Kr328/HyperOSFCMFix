package android.app;

import android.app.IUidObserver;
import android.app.ContentProviderHolder;

interface IActivityManager {
    void registerUidObserver(in IUidObserver observer, int which, int cutpoint, in String callingPackage);
    void unregisterUidObserver(in IUidObserver observer);

    ContentProviderHolder getContentProviderExternal(in String name, int userId, in IBinder token, in String tag);
}
