pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral(); maven("https://jitpack.io") { content { includeGroup("com.github.tiann") } } } }
rootProject.name = "unified-desktop"
include(":app")
include(":engine", ":black-reflection", ":compiler")
project(":black-reflection").projectDir = file("engine/vendor/source/black-reflection")
project(":compiler").projectDir = file("engine/vendor/source/compiler")
