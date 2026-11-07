plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("com.google.devtools.ksp")
    id("maven-publish")
}

kotlin {
    sourceSets {
        named("jvmCommonMain") {
            dependencies {
                api(project(":ethereumkit"))
                implementation(project(":erc20kit"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kermit)

                implementation("io.reactivex.rxjava2:rxjava:2.2.19")
                implementation("com.squareup.retrofit2:retrofit:2.9.0")
                implementation("com.squareup.retrofit2:adapter-rxjava2:2.9.0")
                implementation("com.squareup.retrofit2:converter-gson:2.9.0")
                implementation("com.squareup.okhttp3:logging-interceptor:4.9.0")
                implementation("com.squareup.retrofit2:converter-scalars:2.9.0")
                implementation("com.google.code.gson:gson:2.9.0")
            }
        }
        named("androidUnitTest") {
            dependencies {
                implementation(libs.junit)
            }
        }
        named("androidInstrumentedTest") {
            dependencies {
                implementation("androidx.test.ext:junit:1.1.3")
                implementation("androidx.test:runner:1.4.0")
            }
        }
    }
}

android {
    namespace = "io.horizontalsystems.oneinchkit"
}
