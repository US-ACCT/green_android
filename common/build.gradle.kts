
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.kmp.nativecoroutines)
    alias(libs.plugins.app.cash.sqldelight)
    alias(libs.plugins.nativeCocoapods)
}

compose.resources {
    publicResClass = true
}

sqldelight {
    databases {
        create("WalletDB") {
            packageName.set("com.blockstream.common.database.wallet")
            srcDirs.setFrom("src/commonMain/database_wallet")
        }
        create("LocalDB") {
            packageName.set("com.blockstream.common.database.local")
            srcDirs.setFrom("src/commonMain/database_local")
        }
    }
    linkSqlite.set(true)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvmToolchain(libs.versions.jvm.get().toInt())

    androidTarget()

    jvm()

    val xcf = XCFramework()
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        iosX64(),
    ).forEach {
        it.binaries.framework {
            baseName = "Common"
            xcf.add(this)
            isStatic = true
        }

        val platform = when (it.targetName) {
            "iosX64" -> "ios_simulator_x86"
            "iosSimulatorArm64" -> "ios_simulator_arm64"
            "iosArm64" -> "ios_arm64"
            else -> error("Unsupported target $name")
        }

        it.compilations["main"].cinterops {
            create("gdkCInterop") {
                defFile(project.file("src/nativeInterop/cinterop/gdk.def"))
                includeDirs(project.file("src/include"), project.file("src/libs/$platform"))
            }
        }
    }

    cocoapods {
        version = "2.0"
        ios.deploymentTarget = "15.3"

        pod("Countly") {
            source = git("https://github.com/angelix/countly-sdk-ios") {
                commit = "1892410d13fceccd7cf91f803f06f110efc215b3"
            }

            // Support for Objective-C headers with @import directives
            // https://kotlinlang.org/docs/native-cocoapods-libraries.html#support-for-objective-c-headers-with-import-directives
            extraOpts += listOf("-compiler-option", "-fmodules")
        }
    }

    sourceSets {

        all {
            languageSettings.apply {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
                optIn("kotlin.io.encoding.ExperimentalEncodingApi")
                optIn("kotlin.experimental.ExperimentalObjCName")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("kotlinx.coroutines.FlowPreview")
                optIn("kotlin.ExperimentalStdlibApi")
            }
        }

        commonMain.dependencies {
            /**  --- Modules ---------------------------------------------------------------------------- */
            implementation(project(":ui-common"))
            api(project(":jade"))
            /** ----------------------------------------------------------------------------------------- */

            /**  --- Kotlin & KotlinX ------------------------------------------------------------------- */
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.serialization.cbor)
            api(libs.kotlinx.datetime)
            /** ----------------------------------------------------------------------------------------- */

            /**  --- Ktor ------------------------------------------------------------------------------- */
            api(libs.ktor.client.core)
            /** ----------------------------------------------------------------------------------------- */

            /**  --- Compose ---------------------------------------------------------------------------- */
            api(compose.components.resources)
            /** ----------------------------------------------------------------------------------------- */

            /**  --- Koin   ----------------------------------------------------------------------------- */
            api(project.dependencies.platform(libs.koin.bom))
            api(libs.koin.core)
            /** ----------------------------------------------------------------------------------------- */

            /**  --- Voyager ---------------------------------------------------------------------------- */
            // Required for iOS target compilation
            compileOnly(compose.runtime)
            compileOnly(compose.runtimeSaveable)
            /** ----------------------------------------------------------------------------------------- */

            /**  --- Breez ------------------------------------------------------------------------------ */
            api(libs.breez.sdk.kmp)
            /** ----------------------------------------------------------------------------------------- */

            /**  --- Misc. ------------------------------------------------------------------------------ */
            api(libs.stately.concurrent.collections)
            api(libs.sqldelight.coroutines.extensions)
            api(libs.kmp.observableviewmodel)
            api(libs.uri.kmp)
            api(libs.uuid)
            api(libs.multiplatform.settings)
            api(libs.okio) // Filesystem
            api(libs.kermit)
            api(libs.state.keeper)
            api(libs.kase64) // base64
            api(libs.ksoup.entites) // html entities
            api(libs.kable.core)
            api(libs.kotlincrypto.hash.md)
            api(libs.kotlincrypto.hash.sha2)
            implementation(libs.compose.action.menu)
            implementation(libs.phosphor.icon)

            implementation(libs.tuulbox.coroutines)
            /** ----------------------------------------------------------------------------------------- */
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.koin.test)

            compileOnly(compose.runtime)
            compileOnly(compose.runtimeSaveable)
        }

        val jvmMain by getting
        jvmMain.dependencies {
            api(libs.kotlinx.coroutines.swing)
            implementation(compose.desktop.currentOs)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.ktor.client.java)
        }

        androidMain.dependencies {
            implementation(project(":gdk"))
            implementation(libs.sqldelight.android.driver)
            api(libs.koin.android)
            api(libs.androidx.biometric)

            api(libs.androidx.preference.ktx)
            implementation(libs.ktor.client.android)

            /**  --- Breez FDroid ----------------------------------------------------------------------- */
            // Temp fix for FDroid breez dependencies
            // api(libs.breez.sdk.android.get().toString()) { exclude(group = "net.java.dev.jna", module = "jna") }
            // implementation("${libs.jna.get()}@aar")
            /** ----------------------------------------------------------------------------------------- */
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
                implementation(libs.koin.test)
                implementation(libs.koin.test.junit4)
                implementation(libs.mockk)
            }
        }

        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
            implementation(libs.ktor.client.darwin)
        }
    }
}

task("fetchIosBinaries") {
    doFirst{
        val exists = project.file("src/include").exists() && project.file("src/libs").exists()
        if (!exists) {
            exec {
                commandLine("./fetch_ios_binaries.sh")
            }
        }else{
            print("-- Skipped --")
        }
    }
    outputs.upToDateWhen { false }
}

tasks.configureEach {
    if(name.contains("cinterop")){
        dependsOn("fetchIosBinaries")
    }
}

android {
    namespace = "com.blockstream.common"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }
}

task("useBlockstreamKeys") {
    doLast {
        println("AppKeys: Use Blockstream Keys")
        rootProject.file("contrib/blockstream_keys.txt")
            .copyTo(project.file("src/commonMain/composeResources/files/app_keys.txt"), overwrite = true)
    }
}

task("appKeys") {
    doFirst {
        val appKeys = project.file("src/commonMain/composeResources/files/app_keys.txt")
        if (appKeys.exists()) {
            println("AppKeys: ✔")
        } else {
            println("AppKeys: Use empty key file")
            appKeys.createNewFile()
        }
    }
    outputs.upToDateWhen { false }
}

tasks.getByName("preBuild").dependsOn(tasks.getByName("appKeys"))

tasks.getByName("clean").doFirst {
    delete(project.file("src/include"))
    delete(project.file("src/libs"))
}