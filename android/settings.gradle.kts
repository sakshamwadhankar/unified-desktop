pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral(); maven("https://jitpack.io") { content { includeGroup("com.github.tiann") } } } }
rootProject.name = "unified-desktop"
include(":app")
include(":engine", ":engine-black-reflection", ":engine-compiler")
project(":engine-black-reflection").projectDir = file("engine/vendor/source/black-reflection")
project(":engine-compiler").projectDir = file("engine/vendor/source/compiler")
