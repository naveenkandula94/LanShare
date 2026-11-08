# GitHub Actions guide

Workflow: `.github/workflows/android.yml`

Triggers:
- Push to `main`
- Manual run through **Actions → Build LAN Share APK → Run workflow**

The workflow uses:
- `actions/checkout@v4`
- `actions/setup-java@v4` with Temurin JDK 17
- `gradle/actions/setup-gradle@v4` with Gradle 8.11.1
- `actions/upload-artifact@v4`

It builds the debug APK, checks that the file exists and is non-empty, validates its ZIP structure, then uploads it for 14 days. No signing secrets are required for a debug APK. This is not a Play Store release workflow.
