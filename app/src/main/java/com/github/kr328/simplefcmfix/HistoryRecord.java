package com.github.kr328.simplefcmfix;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

public record HistoryRecord(long timestamp, Action action, Cause cause) implements Parcelable {
    public static final Creator<HistoryRecord> CREATOR = new Creator<>() {
        @Override
        public HistoryRecord createFromParcel(final Parcel in) {
            return new HistoryRecord(in);
        }

        @Override
        public HistoryRecord[] newArray(final int size) {
            return new HistoryRecord[size];
        }
    };

    public HistoryRecord(final Parcel in) {
        this(in.readLong(), Action.values()[in.readInt()], Cause.values()[in.readInt()]);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull final Parcel dest, final int flags) {
        dest.writeLong(timestamp);
        dest.writeInt(action.ordinal());
        dest.writeInt(cause.ordinal());
    }

    public enum Action {
        INJECT,
        REMOVE,
        RECONNECT,
    }

    public enum Cause {
        MANUAL,
        EVENT,
        WATCHDOG,
    }
}
