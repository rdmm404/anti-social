pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor is only published there.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.TeamNewPipe.*") }
        }
    }
}

rootProject.name = "anti-social-tv"

// core: shared with the phone app and, one day, iOS. No Android or YouTube in it.
include(":core")
// youtube: extraction with NewPipeExtractor, behind core's YouTube interface.
include(":youtube")
// playback: Media3, playing what youtube extracted.
include(":playback")
// tv: the Fire TV screens.
include(":tv")
