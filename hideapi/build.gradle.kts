plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.github.kr328.simplefcmfix.hideapi"

    enableKotlin = false

    buildFeatures {
        aidl = true
    }
}
