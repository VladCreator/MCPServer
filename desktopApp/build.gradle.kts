import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

version = "0.4.1"

val iconDirectory = layout.buildDirectory.dir("desktop-icon/tester.iconset")
val createIconDirectory = tasks.register<Exec>("createIconDirectory") {
    commandLine("/bin/mkdir", "-p", iconDirectory.get().asFile)
}
val iconSizes = listOf(16, 32, 128, 256, 512)
val resizeIcons = iconSizes.flatMap { size ->
    listOf(1, 2).map { density ->
        tasks.register<Exec>("resizeIcon${size}x$density") {
            dependsOn(createIconDirectory)
            val name = "icon_${size}x$size${if (density == 2) "@2x" else ""}.png"
            commandLine("/usr/bin/sips", "-z", size * density, size * density,
                rootProject.file("androidApp/src/main/ic_launcher-playstore.png"),
                "--out", iconDirectory.get().file(name).asFile)
        }
    }
}
val desktopIcon = layout.buildDirectory.file("desktop-icon/tester.icns")
val generateDesktopIcon = tasks.register<Exec>("generateDesktopIcon") {
    dependsOn(resizeIcons)
    commandLine("/usr/bin/iconutil", "-c", "icns", iconDirectory.get().asFile, "-o", desktopIcon.get().asFile)
}

kotlin {
    jvm()
    jvmToolchain(21)
    sourceSets {
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.kotlinx.serialization.json)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            implementation("org.bouncycastle:bcpkix-jdk18on:1.80.2")
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.vlad.tester.desktop.MainKt"
        javaHome = providers.environmentVariable("JAVA_HOME").orElse(providers.systemProperty("java.home")).get()
        buildTypes.release.proguard {
            // Rewriting signed crypto libraries invalidates their embedded JAR signatures.
            isEnabled.set(false)
        }
        nativeDistributions {
            modules("jdk.httpserver", "java.desktop", "jdk.crypto.ec")
            targetFormats(TargetFormat.Dmg)
            packageName = "Tester Desktop"
            // jpackage requires a positive major version even for prerelease apps.
            packageVersion = "1.4.1"
            macOS {
                bundleID = "com.vlad.tester.desktop"
                packageBuildVersion = "20"
                iconFile.set(desktopIcon)
            }
        }
    }
}

val normalizeMacVersion = tasks.register<Exec>("normalizeMacVersion") {
    dependsOn("createDistributable")
    commandLine("/usr/bin/plutil", "-replace", "CFBundleShortVersionString", "-string", "0.4.1",
        layout.buildDirectory.file("compose/binaries/main/app/Tester Desktop.app/Contents/Info.plist").get().asFile)
}

tasks.matching { it.name in setOf("packageDmg", "runDistributable", "packageDistributionForCurrentOS") }
    .configureEach { dependsOn(normalizeMacVersion) }

tasks.matching { it.name in setOf("createDistributable", "createReleaseDistributable") }
    .configureEach { dependsOn(generateDesktopIcon) }
tasks.named<Copy>("jvmProcessResources") {
    from(rootProject.file("androidApp/src/main/ic_launcher-playstore.png")) { rename { "tester-icon.png" } }
}
