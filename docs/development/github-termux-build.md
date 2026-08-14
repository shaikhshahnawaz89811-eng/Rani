# Termux + GitHub workflow

1. Extract the ZIP in Termux.
2. Create or enter the Git repository.
3. Commit and push the project to the `main` branch.
4. GitHub Actions uses Ubuntu + JDK 17 + Gradle 8.11.1.
5. The workflow runs `gradle testDebugUnitTest` and `gradle assembleDebug`.
6. The debug APK is uploaded as the `sa-ai-desktop-debug-apk` workflow artifact.

Termux is only a development/build convenience. The installed Android app does not depend on Termux.
