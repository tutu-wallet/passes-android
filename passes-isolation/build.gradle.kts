plugins {
    alias(libs.plugins.walt.passes.android.library)
    alias(libs.plugins.walt.passes.quality)
}

android {
    namespace = "is.walt.passes.isolation"

    testOptions {
        // Same matrix as passes-image; the seal tests need memfd_create and skip below API 30.
        managedDevices {
            localDevices {
                for (api in listOf(28, 31, 34, 36)) {
                    create("api${api}google") {
                        device = "Pixel 2"
                        apiLevel = api
                        systemImageSource = "google"
                    }
                }
            }
        }
    }
}

dependencies {
    // The bind-session API hands a typed client back to callers via a suspend function, so
    // coroutines is part of this module's public surface.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
