plugins {
    base
}

val releaseDirectory = layout.projectDirectory.dir("dist")

val cleanReleaseArchives by tasks.registering(Delete::class) {
    delete(fileTree(releaseDirectory) {
        include("InputBridge-*.zip")
    })
}

tasks.register<Copy>("buildPlugin") {
    group = "build"
    description = "Builds and stages the installable InputBridge plugin archive."
    dependsOn(":plugin:buildPlugin", cleanReleaseArchives)
    from(project(":plugin").layout.buildDirectory.dir("distributions")) {
        include("InputBridge-*.zip")
    }
    into(releaseDirectory)
    outputs.upToDateWhen { false }
}

tasks.register("verifyProject") {
    group = "verification"
    description = "Compiles the device server and runs all plugin checks."
    dependsOn(":server:buildServer", ":plugin:check")
}
