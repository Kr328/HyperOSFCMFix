package com.github.kr328.simplefcmfix.compat;

import android.os.UserHandle;

import com.github.kr328.simplefcmfix.refine.Refine;

@Refine
public class UserCompat {
    @Refine.InvokeStatic(UserHandle.class)
    public static int getUserId(final int uid) {
        throw new IllegalArgumentException("Stub!");
    }
}
