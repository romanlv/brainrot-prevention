# Deviations from SPEC.md

## Toolchain
- **compileSdk 37.1** (`compileSdk { version = release(37) { minorApiLevel = 1 } }`), not 37.0: Compose BOM 2026.11.01 (foundation/ui 1.13.0) requires compiling against 37.1. targetSdk stays 37; the emulator image stays `android-37.0;google_apis`.

## Open
