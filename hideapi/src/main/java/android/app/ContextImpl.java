package android.app;

import android.content.Context;
import android.content.ContextWrapper;

class ContextImpl extends ContextWrapper {
    public ContextImpl(final Context base) {
        super(base);
    }
}
