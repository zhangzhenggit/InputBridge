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
    }
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = pluginSinceBuild
        }
    }
}

val serverArtifact = project(":server").layout.buildDirectory.file("dist/inputbridge-server.jar")

tasks {
    patchPluginXml {
        sinceBuild = pluginSinceBuild
    }

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
