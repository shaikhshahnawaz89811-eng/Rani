plugins {
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Chaquopy: bundles a real CPython interpreter + stdlib inside the APK itself, so
    // `python`/`python3` in the Terminal and the Developer Workspace "Run" button execute for
    // real on-device, with no Termux/external app and no reliance on a system python binary
    // (Android does not ship one).
    id("com.chaquo.python") version "15.0.1" apply false
}
