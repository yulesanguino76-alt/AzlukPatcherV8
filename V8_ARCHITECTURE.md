# AzlukPatcher V8

AzlukPatcher V8 is the next architectural iteration of the supplied V7 project.

## What was reviewed

The supplied `AzlukPatcherV73` project was inspected together with the supplied
`patchers.zip` collection. The collection contains multiple packaged third-party
tools and assets, including APK editors, data editors, patchers, memory/debugging
tools and game-oriented utilities.

V8 uses the *capabilities as architectural inspiration* rather than copying or
rebranding third-party binaries, proprietary code, certificates, or assets.

## V8 changes

- Reworked visual identity and Material 3 dark UI.
- Added an Azluk Patch Pack catalog in `assets/azluk_patchpacks.json`.
- Added an explicit `PatchPackCatalog` loader.
- Refactored the engine around streaming ZIP processing.
- Added deterministic APK inspection and findings.
- Added SHA-256 output verification.
- Added stale APK signature-metadata cleanup before local signing.
- Kept native libraries and `resources.arsc` stored when repacking.
- Added a clearer V8 versioned output name.
- Preserved the existing app scanner/detail/tools/navigation architecture.

## Important implementation boundary

The V8 engine intentionally does not implement license bypass, purchase bypass,
signature-integrity bypass, credential theft, or security-control bypass logic.
Detection/inspection of those references is retained because it is useful for
analysis and debugging.

The local signing certificate is a development certificate. A production build
should use a private signing key supplied by the project owner.

## Build

Use the included Gradle wrapper:

    ./gradlew assembleDebug

The Android project remains a normal Gradle Android application.
