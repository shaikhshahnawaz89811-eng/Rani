plugins {
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    // Chaquopy: bundles a real CPython interpreter + stdlib inside the APK itself, so
    // `python`/`python3` in the Terminal and the Developer Workspace "Run" button execute for
    // real on-device, with no Termux/external app and no reliance on a system python binary
    // (Android does not ship one). Version/compatibility confirmed against the official
    // Chaquopy version table: 17.0 supports AGP 7.3-9.2 (we use 8.9.2) and minSdk 24+ (we use 26).
    id("com.chaquo.python") version "17.0.0" apply false
}
