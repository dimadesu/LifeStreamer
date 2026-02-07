pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "LifeStreamer"
include(":app")

// Include StreamPack as a composite build
includeBuild("StreamPack") {
    dependencySubstitution {
        substitute(module("io.github.thibaultbee.streampack:streampack-core"))
            .using(project(":streampack-core"))
        substitute(module("io.github.thibaultbee.streampack:streampack-ui"))
            .using(project(":streampack-ui"))
        substitute(module("io.github.thibaultbee.streampack:streampack-services"))
            .using(project(":streampack-services"))
        substitute(module("io.github.thibaultbee.streampack:streampack-rtmp"))
            .using(project(":streampack-rtmp"))
        substitute(module("io.github.thibaultbee.streampack:streampack-srt"))
            .using(project(":streampack-srt"))
    }
}
 