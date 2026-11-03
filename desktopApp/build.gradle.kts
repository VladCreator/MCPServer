import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

version = "0.4.0"

kotlin {
    jvm()
    jvmToolchain(21)
    sourceSets {
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.material3)
            implementation(libs.kotlinx.serialization.json)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.vlad.tester.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Tester Desktop"
            // jpackage requires a positive major version even for prerelease apps.
            packageVersion = "1.4.0"
            macOS {
                bundleID = "com.vlad.tester.desktop"
                packageBuildVersion = "19"
            }
        }
    }
}

val normalizeMacVersion = tasks.register<Exec>("normalizeMacVersion") {
    dependsOn("createDistributable")
    commandLine("/usr/bin/plutil", "-replace", "CFBundleShortVersionString", "-string", "0.4.0",
        layout.buildDirectory.file("compose/binaries/main/app/Tester Desktop.app/Contents/Info.plist").get().asFile)
}

tasks.matching { it.name in setOf("packageDmg", "runDistributable", "packageDistributionForCurrentOS") }
    .configureEach { dependsOn(normalizeMacVersion) }
