# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Layout

This repo contains two independent artifacts:

- `eyescroll-android/` — Android app (Kotlin) for hands-free social media scrolling via eye tracking
- `index.html` + `CNAME` — Static portfolio/landing site served at `ahmads.art`

All Android work lives under `eyescroll-android/`.

## Android Project: EyeScroll

### Build Commands

```bash
cd eyescroll-android

# Download the required MediaPipe face landmarker model before first build
./download_model.sh          # writes to app/src/main/assets/face_landmarker.task

./gradlew assembleDebug      # build debug APK
./gradlew assembleRelease    # build release APK
./gradlew lint               # run Android lint
./gradlew test               # unit tests (none exist yet)
./gradlew connectedAndroidTest  # instrumentation tests on device/emulator
```

### Architecture

The app runs a **foreground service** that binds the camera and pushes gaze state to the UI and accessibility layer via `StateFlow`.

```
MainActivity
  └─ observes EyeTrackingService.* StateFlows (gaze, tracking status, model status)
  └─ starts/stops EyeTrackingService

EyeTrackingService  (foreground service, camera permission)
  └─ owns CameraX pipeline
  └─ owns GazeDetector (MediaPipe FaceLandmarker)
  └─ emits: gazeStateFlow, isTrackingFlow, modelStatusFlow

GazeDetector
  └─ wraps MediaPipe FaceLandmarker
  └─ applies exponential smoothing to blend shape values
  └─ fires callback after dwell timer when "look up" threshold is met
  └─ configurable: lookUpThreshold (0.15–0.50), dwellTimeMs (500–2500)

EyeScrollAccessibilityService  (AccessibilityService)
  └─ receives trigger from EyeTrackingService
  └─ injects a swipe-up gesture into the foreground app

EyeGazeView  (custom View)
  └─ animated iris + dwell progress ring drawn on Canvas
  └─ updated directly from gazeStateFlow values
```

**Key data flow:** `GazeDetector` callback → `EyeTrackingService` StateFlow → `MainActivity` UI + `EyeScrollAccessibilityService` gesture injection.

### Important Implementation Details

- **Model bootstrap:** `download_model.sh` fetches `face_landmarker.task` from Google's CDN. The service checks for this file on start and emits a `modelStatusFlow` state; `MainActivity` gates the "enable" toggle on model readiness.
- **Namespace / App ID:** `art.ahmads.eyescroll`
- **Min SDK 26 (Android 8.0), Target/Compile SDK 35, JVM 17**
- **View Binding** is enabled — use `ActivityMainBinding`, not `findViewById`.
- The camera pipeline runs on a dedicated `Executor`; gaze callbacks arrive off the main thread. Update StateFlows (not UI) from callbacks.
- The accessibility service config lives in `res/xml/accessibility_service_config.xml` and must be updated if new gesture types or packages are added.
- Dark Material3 theme; accent color `#C4A97A`, background `#0E0E0D` (defined in `colors.xml`).
