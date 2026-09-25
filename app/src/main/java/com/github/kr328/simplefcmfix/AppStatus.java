package com.github.kr328.simplefcmfix;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.Objects;

public record AppStatus(
        @NonNull String packageName,
        boolean isSystemApp,
        boolean supportsFcm,
        boolean fromPlayStore,
        boolean noRestriction,
        boolean allowAutoStart
) implements Parcelable {
    public static final Creator<AppStatus> CREATOR = new Creator<>() {
        @Override
        public AppStatus createFromParcel(final Parcel in) {
            return new AppStatus(in);
        }

        @Override
        public AppStatus[] newArray(final int size) {
            return new AppStatus[size];
        }
    };

    public AppStatus(final Parcel in) {
        this(
                Objects.requireNonNullElse(in.readString(), "android"),
                in.readBoolean(),
                in.readBoolean(),
                in.readBoolean(),
                in.readBoolean(),
                in.readBoolean()
        );
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull final Parcel dest, final int flags) {
        dest.writeString(packageName);
        dest.writeBoolean(isSystemApp);
        dest.writeBoolean(supportsFcm);
        dest.writeBoolean(fromPlayStore);
        dest.writeBoolean(noRestriction);
        dest.writeBoolean(allowAutoStart);
    }
}
