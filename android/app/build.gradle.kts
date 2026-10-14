plugins { id("com.android.application") }

android { namespace = "dev.unifieddesktop.app"; compileSdk = 36
    defaultConfig { applicationId = "dev.unifieddesktop.app"; minSdk = 29; targetSdk = 36; versionCode = 1; versionName = "0.1" }
}
dependencies { implementation(project(":engine")); testImplementation("junit:junit:4.13.2") }
