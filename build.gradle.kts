plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

group = "co.screenmate.can"
version = "0.1.0"

android {
    namespace = "co.screenmate.can.tx.client"
    compileSdk = 34
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // dadb bundles META-INF entries that collide on merge. Consumers need the same exclude in
    // their own packaging block; this one covers the library's own artifact.
    packaging { resources.excludes += "META-INF/*" }
}

dependencies {
    // Pure-JVM ADB client — talks to the box's own root adbd over loopback.
    api("dev.mobile:dadb:1.2.9")
}
