package com.github.kr328.simplefcmfix;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

public record ApplierConfig(boolean autoAllowFCMWakeForPlayStoreApps) implements Parcelable {
    public static final Creator<ApplierConfig> CREATOR = new Creator<>() {
        @Override
        public ApplierConfig createFromParcel(final Parcel in) {
            return new ApplierConfig(in);
        }

        @Override
        public ApplierConfig[] newArray(final int size) {
            return new ApplierConfig[size];
        }
    };

    public ApplierConfig(final Parcel in) {
        this(in.readBoolean());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull final Parcel dest, final int flags) {
        dest.writeBoolean(autoAllowFCMWakeForPlayStoreApps);
    }
}
