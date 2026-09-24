plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.github.kr328.simplefcmfix"

    enableKotlin = false

    defaultConfig {
        applicationId = "com.github.kr328.simplefcmfix"
        versionCode = 10009
        versionName = "1.9"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        resources {
            excludes.add("kotlin/**")
        }
    }
}

dependencies {
    compileOnly(project(":hideapi"))

    implementation(libs.androidx.annotation)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
