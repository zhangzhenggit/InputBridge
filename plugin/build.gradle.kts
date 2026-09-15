import java.util.Properties

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

val pluginGroup: String by project
val pluginVersion: String by project
val pluginSinceBuild: String by project

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf(File::isFile)?.inputStream()?.use(::load)
}
val studioPath = providers.gradleProperty("studioPath")
    .orElse(providers.environmentVariable("INPUT_BRIDGE_STUDIO_PATH"))
    .orElse(provider { localProperties.getProperty("studioPath").orEmpty() })
    .map(String::trim)
    .orNull
    ?.takeIf(String::isNotEmpty)
    ?: error("Missing studioPath. Set INPUT_BRIDGE_STUDIO_PATH, -PstudioPath, or local.properties.")

group = pluginGroup
version = pluginVersion

base {
    archivesName.set("InputBridge")
}

repositories {
    mavenCentral()
    intellijPlatform {
        localPlatformArtifacts()
        intellijDependencies()
    }
}

dependencies {
    intellijPlatform {
        local(studioPath)
        bundledPlugin("org.jetbrains.android")
        pluginVerifier()
        zipSigner()
    }
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

/** Renders the CHANGELOG.md section for the current version as the Marketplace change notes. */
val releaseChangeNotes = provider {
    val changelog = rootProject.file("CHANGELOG.md").readText()
    val section = Regex("^## ${Regex.escape(pluginVersion)}\\s*$(.*?)(?=^## |\\z)", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
        .find(changelog)
        ?.groupValues
        ?.get(1)
        ?: error("CHANGELOG.md has no section for version $pluginVersion.")
    val items = section.lines().map(String::trim).filter { it.startsWith("- ") }.map { item ->
        item.removePrefix("- ")
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace(Regex("`([^`]+)`"), "<code>$1</code>")
    }
    check(items.isNotEmpty()) { "CHANGELOG.md section $pluginVersion has no items." }
    items.joinToString(separator = "", prefix = "<ul>", postfix = "</ul>") { "<li>$it</li>" }
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        changeNotes = releaseChangeNotes
        ideaVersion {
            sinceBuild = pluginSinceBuild
        }
    }

    // Signing and publishing secrets come only from the environment and never enter the repository.
    signing {
        certificateChainFile = layout.file(providers.environmentVariable("INPUT_BRIDGE_CERTIFICATE_CHAIN_FILE").map(::File))
        privateKeyFile = layout.file(providers.environmentVariable("INPUT_BRIDGE_PRIVATE_KEY_FILE").map(::File))
        password = providers.environmentVariable("INPUT_BRIDGE_PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("INPUT_BRIDGE_PUBLISH_TOKEN")
    }

    // The release lists are intentionally empty (see gradle.properties), so verification uses the local IDE.
    pluginVerification {
        ides {
            local(studioPath)
        }
    }
}

val serverArtifact = project(":server").layout.buildDirectory.file("dist/inputbridge-server.jar")

tasks {
    processResources {
        dependsOn(":server:buildServer")
        from(serverArtifact) {
            into("device")
            rename { "inputbridge-server.jar" }
        }
    }

    test {
        useJUnitPlatform()
    }

    named<Zip>("buildPlugin") {
        archiveBaseName.set("InputBridge")
        doFirst {
            fileTree(layout.buildDirectory.dir("distributions")).matching { include("*.zip") }.forEach(File::delete)
        }
    }
}
