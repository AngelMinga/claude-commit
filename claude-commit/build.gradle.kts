import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.7.2"
}

group = "com.angelminga"
version = "1.12.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.2")
        bundledModule("intellij.platform.vcs.impl")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    // ./gradlew verifyPlugin: the oldest supported line and the latest release. Fails on everything JetBrains
    // Marketplace flags, including internal API usage.
    pluginVerification {
        failureLevel = listOf(
            FailureLevel.COMPATIBILITY_PROBLEMS,
            FailureLevel.INTERNAL_API_USAGES,
            FailureLevel.NON_EXTENDABLE_API_USAGES,
            FailureLevel.OVERRIDE_ONLY_API_USAGES,
            FailureLevel.INVALID_PLUGIN,
        )
        // MISSING_DEPENDENCIES is left out on purpose: the optional com.intellij.platform.vcs dependency only exists
        // since 2026.3, so it is reported as missing on older IDEs even though the plugin is compatible there.
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6")
            ide(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2.3")
        }
    }
    // ./gradlew publishPlugin with PUBLISH_TOKEN (Marketplace → Profile → My Tokens).
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

kotlin {
    jvmToolchain(21)
}
