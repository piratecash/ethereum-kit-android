plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("maven-publish")
}

kotlin {
    sourceSets {
        named("jvmCommonMain") {
            dependencies {
                api(project(":ethereumkit"))
                implementation("com.google.code.gson:gson:2.9.0")
                implementation("io.reactivex.rxjava2:rxjava:2.2.19")
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.rx2)
                implementation(libs.kermit)
                implementation(libs.room.runtime)
                // Needed only to reference okhttp3.EventListener.Factory in threaded signatures; not published
                // to the runtime graph (the consuming app already provides okhttp).
                compileOnly("com.squareup.okhttp3:okhttp:4.9.0")
            }
        }
        named("androidUnitTest") {
            kotlin.srcDir("src/test/sharedFixture/kotlin")
            kotlin.srcDir("$rootDir/ethereumkit/src/test/fixtureSupport/kotlin")
            dependencies {
                implementation(libs.junit)
                implementation(libs.robolectric)
                implementation(libs.androidx.test.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        named("androidInstrumentedTest") {
            dependencies {
                implementation("androidx.test.ext:junit:1.1.3")
                implementation("androidx.test.espresso:espresso-core:3.4.0")
            }
        }
        named("desktopTest") {
            kotlin.srcDir("src/test/sharedFixture/kotlin")
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

android {
    namespace = "io.horizontalsystems.merkleiokit"
}
