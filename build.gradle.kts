import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

subprojects {
    val appCompileSdk = 37
    val appTargetSdk = 37
    val appMinSdk = 33

    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension> {
            compileSdk = appCompileSdk

            defaultConfig {
                minSdk = appMinSdk
                targetSdk = appTargetSdk
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }

    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            compileSdk = appCompileSdk

            defaultConfig {
                minSdk = appMinSdk
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
}
