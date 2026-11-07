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
                implementation(libs.kotlinx.coroutines.rx2)
                implementation(libs.kermit)
                implementation("io.reactivex.rxjava2:rxjava:2.2.19")
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
                implementation("androidx.test.espresso:espresso-core:3.4.0")
            }
        }
    }
}

android {
    namespace = "io.horizontalsystems.uniswapkit"
}
