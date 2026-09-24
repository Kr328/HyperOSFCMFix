-dontobfuscate

-keepclasseswithmembers,allowoptimization class com.github.kr328.simplefcmfix.ShizukuRemote {
    public <init>(...);
}

-keep,allowoptimization class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
