plugins { id("com.android.application") }

android { namespace = "dev.unifieddesktop.app"; compileSdk = 36
    defaultConfig { applicationId = "dev.unifieddesktop.app"; minSdk = 29; targetSdk = 36; versionCode = 1; versionName = "0.1" }
    // libbox64.so must be extracted to nativeLibraryDir so it can be executed (C1: only
    // files from nativeLibraryDir run; everything else is data).
    packaging { jniLibs { useLegacyPackaging = true } }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies { implementation(project(":engine")); implementation("org.tukaani:xz:1.10"); testImplementation("junit:junit:4.13.2") }
