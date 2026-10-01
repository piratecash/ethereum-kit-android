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
                implementation("io.reactivex.rxjava2:rxjava:2.2.19")
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
                implementation(libs.mockk)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        // Device tests of the SQLCipher wiring for EthereumKit and Erc20Kit; never part of the published AAR.
        named("androidInstrumentedTest") {
            kotlin.srcDir("src/test/sharedFixture/kotlin")
            kotlin.srcDir("$rootDir/ethereumkit/src/test/sharedFixture/kotlin")
            kotlin.srcDir("$rootDir/ethereumkit/src/test/kitSupport/kotlin")
            dependencies {
                // assertThrows needs JUnit 4.13; androidx.test.ext:junit 1.1.3 brings 4.12.
                implementation(libs.junit)
                implementation("androidx.test.ext:junit:1.1.3")
                implementation("androidx.test.espresso:espresso-core:3.4.0")
            }
        }
        named("desktopTest") {
            kotlin.srcDir("src/test/sharedFixture/kotlin")
            kotlin.srcDir("$rootDir/ethereumkit/src/test/kitSupport/kotlin")
            kotlin.srcDir("$rootDir/ethereumkit/src/test/migrationSupport/kotlin")
            resources.srcDir("src/test/resources")
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.sqlite.bundled)
                implementation(libs.sqlcipher.driver)
            }
        }
    }
}

android {
    namespace = "io.horizontalsystems.erc20kit"

    sourceSets {
        // The plaintext fixtures; read-only, shared with the host tests.
        getByName("androidTest").resources.srcDir("src/test/resources")
        getByName("androidTest").resources.srcDir("$rootDir/ethereumkit/src/test/resources")
    }
}
