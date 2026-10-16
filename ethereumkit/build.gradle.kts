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
                api(libs.kermit)
                implementation(libs.bcpkix.jdk15to18)
                implementation(libs.hd.wallet.kit)

                implementation("io.reactivex.rxjava2:rxjava:2.2.19")
                implementation("com.squareup.retrofit2:retrofit:2.9.0")
                implementation("com.squareup.retrofit2:adapter-rxjava2:2.9.0")
                implementation("com.squareup.retrofit2:converter-gson:2.9.0")
                implementation("com.squareup.okhttp3:logging-interceptor:4.9.0")
                implementation("com.squareup.retrofit2:converter-scalars:2.9.0")
                implementation("com.google.code.gson:gson:2.9.0")

                val scarletVersion = "0.1.12"
                implementation("com.tinder.scarlet:scarlet:$scarletVersion")
                implementation("com.tinder.scarlet:websocket-okhttp:$scarletVersion")
                implementation("com.tinder.scarlet:stream-adapter-rxjava2:$scarletVersion")
                implementation("com.tinder.scarlet:message-adapter-gson:$scarletVersion")

                implementation(libs.room.runtime)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.rx2)

                // Eip712
                implementation("org.web3j:crypto:4.9.4") {
                    exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
                }
                api("org.web3j:abi:4.9.8") {
                    exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
                }
            }
        }
        androidMain {
            dependencies {
                // Public API exposes its exceptions and DatabaseMigrationResult.
                api(libs.sqlcipher.room)
                implementation(libs.kotlinx.coroutines.android)
                implementation("androidx.annotation:annotation:1.4.0")
            }
        }
        named("desktopMain") {
            dependencies {
                api(libs.sqlcipher.room)
                runtimeOnly(libs.secp256k1.jni.jvm)
            }
        }

        named("androidUnitTest") {
            kotlin.srcDir("src/test/sharedFixture/kotlin")
            kotlin.srcDir("src/test/fixtureSupport/kotlin")
            dependencies {
                implementation(libs.robolectric)
                implementation(libs.androidx.test.core)
                implementation(libs.junit)
                implementation("org.mockito:mockito-core:3.3.3")
                implementation("com.nhaarman:mockito-kotlin-kt1.1:1.6.0")
                // Mockito 3.3.3 cannot read Java 17 bytecode; new tests use mockk, as :erc20kit already does.
                implementation(libs.mockk)
                implementation(libs.kotlinx.coroutines.test)
                implementation("com.squareup.okhttp3:mockwebserver:4.9.0")
                runtimeOnly(libs.secp256k1.jni.jvm)
            }
        }
        named("androidInstrumentedTest") {
            dependencies {
                implementation("androidx.test.ext:junit:1.1.3")
                implementation("com.linkedin.dexmaker:dexmaker-mockito-inline:2.28.1")
            }
        }
        named("desktopTest") {
            kotlin.srcDir("src/test/sharedFixture/kotlin")
            kotlin.srcDir("src/test/kitSupport/kotlin")
            kotlin.srcDir("src/test/migrationSupport/kotlin")
            resources.srcDir("src/test/resources")
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test)
                // Plaintext fixtures only; the kit itself opens databases through SQLCipher.
                implementation(libs.sqlite.bundled)
                // Reads and stages encrypted files directly; aligned with sqlcipher-room.
                implementation(libs.sqlcipher.driver)
            }
        }
    }
}

// The Java sources (Keccak digests, BouncyCastle provider) sit in the pre-KMP layout, which only
// AGP's `main` source set knows about; without this the desktop jar silently lacks them.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    sourceSets["desktopMain"].java.srcDir("src/main/java")
}

// Parity with the pre-KMP artifact: the Kotlin Android plugin passed -parameters to javac.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

android {
    namespace = "io.horizontalsystems.ethereumkit"

    testOptions {
        unitTests.all {
            it.jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
        }
    }
}
