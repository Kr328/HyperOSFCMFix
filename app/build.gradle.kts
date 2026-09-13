plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.github.kr328.simplefcmfix"

    enableKotlin = false

    defaultConfig {
        applicationId = "com.github.kr328.simplefcmfix"
        versionCode = 10003
        versionName = "1.3"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    buildTypes {
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
