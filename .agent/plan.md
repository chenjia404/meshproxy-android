# Project Plan

A minimal, small-sized empty Android project named "meshproxy-android". The primary goal is to keep the APK size as small as possible. The app wraps and executes a binary file ('meshproxy.so').

## Project Brief

# Project Brief: meshproxy-android

A lightweight Android wrapper designed to execute and manage a binary mesh proxy with a focus on minimal APK size and high performance.

## Features
- **Binary Execution Wrapper**: Efficiently bundles and executes a standalone binary file ('meshproxy.so') within the Android environment.
- **Foreground Service Management**: Implements a persistent service to ensure the proxy continues running in the background.
- **Minimalist Control Dashboard**: A clean, single-screen interface to toggle the proxy state and view connection status.
- **Real-time Log Monitoring**: Integrated terminal-style view to monitor the binary's output and diagnostic information.

## High-Level Technical Stack
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose with Material Design 3
- **Concurrency**: Kotlin Coroutines
- **Dependency Injection / Code Generation**: KSP (Kotlin Symbol Processing)
- **Lifecycle Management**: Android Architecture Components (ViewModel, LiveData/Flow)
- **Build System**: Gradle Kotlin DSL optimized for small APK size

## Implementation Steps

### Task_1_Binary_Service: Implement the core proxy engine. This includes bundling a placeholder binary in assets, extracting it to internal storage, and creating a Foreground Service to execute and manage the binary process while capturing its output logs.
- **Status:** COMPLETED
- **Updates:** Successfully implemented the core proxy engine.
- **Acceptance Criteria:**
  - Binary extracted successfully to app's internal storage
  - Foreground Service correctly manages binary execution
  - Logs are captured and exposed via a Kotlin Flow
  - Build pass

### Task_2_Dashboard_UI: Develop the main user interface using Jetpack Compose and Material Design 3. Implement a toggle switch to control the proxy service and a terminal-like view for real-time log monitoring.
- **Status:** COMPLETED
- **Updates:** Developed the main user interface for 'meshproxy-android' using Jetpack Compose and Material Design 3.
- **Acceptance Criteria:**
  - Single-screen UI follows Material 3 guidelines
  - Toggle switch starts/stops the Foreground Service
  - Logs update in real-time in the terminal view
  - UI state correctly reflects proxy status

### Task_3_Optimization_Assets: Enhance the app's visual identity and optimize for size. Apply a vibrant Material 3 color scheme with Edge-to-Edge support. Configure R8 for aggressive APK size reduction and create an adaptive app icon.
- **Status:** COMPLETED
- **Updates:** Enhance the app's visual identity and optimize for size.
- **Acceptance Criteria:**
  - Material 3 theme with light/dark support and vibrant colors
  - Full Edge-to-Edge display implemented
  - APK size is minimized via R8/ProGuard
  - Adaptive app icon is present
  - Build pass

### Task_4_Final_Verification: Perform a comprehensive run and verify session. Ensure the proxy binary runs stable, the UI is responsive, and all features align with the project brief.
- **Status:** COMPLETED
- **Updates:** Performed a comprehensive verification of the 'meshproxy-android' app.
- **Acceptance Criteria:**
  - App does not crash during execution
  - All existing tests pass
  - Build pass
  - Application stability and UI requirements verified by critic_agent

### Task_5_Integrate_Real_Binary_and_Cleanup: Replace the placeholder binary with the actual 'meshproxy.so'. Update the BinaryManager and ProxyService to execute this binary. Additionally, remove all unused dependencies (Room, Retrofit, Coil, CameraX, Play Services, etc.) from build.gradle.kts and libs.versions.toml to strictly minimize the APK size.
- **Status:** COMPLETED
- **Updates:** Successfully integrated the real binary and performed a complete cleanup to minimize the APK size:
- **Acceptance Criteria:**
  - 'meshproxy.so' is bundled, extracted, and executed correctly
  - Unused dependencies are removed from the project
  - Project builds successfully
  - APK size is reduced
  - build pass

### Task_6_Final_Run_and_Verify: Perform a final Run and Verify session to ensure the application is stable with the real binary and the stripped-down dependency list. Confirm that logs from 'meshproxy.so' are correctly displayed in the UI.
- **Status:** COMPLETED
- **Updates:** Performed a final Run and Verify session to ensure the application is stable with the real binary and the stripped-down dependency list:
- Verified that `BinaryManager.kt` and `ProxyService.kt` are correctly configured to point to `meshproxy.so`.
- Confirmed that all unused dependencies (Room, Retrofit, Coil, CameraX, etc.) have been removed from `app/build.gradle.kts` and `gradle/libs.versions.toml`.
- Verified the final project builds successfully with `./gradlew clean :app:assembleDebug`.
- Ran `BinaryExtractionTest.kt` to ensure binary extraction logic is correct for `meshproxy.so`.
- Confirmed the Dashboard UI and terminal log viewer are functional and ready for real-time monitoring of the actual binary.
- Validated the Material 3 design, Edge-to-Edge display, and APK size optimization.
- The app is stable and meets all requirements from the project brief.
- **Acceptance Criteria:**
  - App does not crash during execution
  - Logs from the actual binary are visible in the terminal view
  - All existing tests pass
  - Build pass
  - Final stability and size optimization verified
- **Duration:** N/A

