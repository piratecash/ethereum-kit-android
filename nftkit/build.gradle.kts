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
                implementation(libs.room.runtime)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.rx2)
                implementation(libs.kermit)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.android)
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
                implementation("androidx.test:runner:1.4.0")
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
    namespace = "io.horizontalsystems.nftkit"
}
