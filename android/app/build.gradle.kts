plugins { id("com.android.application") }

android { namespace = "dev.unifieddesktop.app"; compileSdk = 36
    defaultConfig { applicationId = "dev.unifieddesktop.app"; minSdk = 29; targetSdk = 36; versionCode = 1; versionName = "0.1" }
}
dependencies { implementation(project(":engine")); implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21"); testImplementation("junit:junit:4.13.2") }
