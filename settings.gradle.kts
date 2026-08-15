import org.gradle.api.initialization.resolve.RepositoriesMode
// NOTE: chaquo.com/maven added so Gradle can resolve the real Chaquopy (embedded CPython for
// Android) plugin and its runtime artifacts. Nothing else in this block was changed.
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal(); maven("https://chaquo.com/maven") } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral(); maven("https://chaquo.com/maven") } }
rootProject.name = "SA-AIDesktop-Android"
include(":app")
