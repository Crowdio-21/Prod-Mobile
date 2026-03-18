# CROWDio Mobile Worker (Android)

Android client + worker runtime for CROWDio distributed execution.

This app provides:

- A mobile worker running as a foreground Android service.
- A monitoring/dashboard UI for jobs, workers, and system status.
- On-device Python task execution via Chaquopy.

## Documentation

- Architecture + setup: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Worker feature parity notes: [WORKER_FEATURES.md](WORKER_FEATURES.md)

## Quick Start

## Prerequisites

- Android Studio (recent stable)
- Java 11 toolchain
- Android SDK (compile SDK 36, min SDK 27)
- Reachable CROWDio foreman host from device/emulator

## Build

Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

Install debug build:

```powershell
.\gradlew.bat installDebug
```

## Run and Configure

1. Launch the app.
2. Open Settings and configure Foreman IP and ports.
3. Start worker from the Tasks tab.
4. Verify worker and task state from Tasks/Dashboard.

Default ports used in configuration:

- HTTP API: `8000`
- Worker WebSocket: `9000`
- Artifact/model transfer: `8001` (when foreman uses upload/model-store endpoints)

## High-Level Architecture

Worker runtime path:

`TasksFragment` -> `MobileWorkerService` -> `WorkerWebSocketClient` -> `TaskProcessor` -> `PythonExecutor`

Dashboard data path:

`MainActivity/Fragments` -> `MainViewModel` -> `CrowdComputeRepository` -> REST/WebSocket clients

Key source locations:

- Worker service: `app/src/main/java/com/example/mcc_phase3/services`
- Communication/protocol: `app/src/main/java/com/example/mcc_phase3/communication`
- Execution/checkpointing: `app/src/main/java/com/example/mcc_phase3/execution`, `app/src/main/java/com/example/mcc_phase3/checkpoint`
- UI + state: `app/src/main/java/com/example/mcc_phase3/ui`
- Python modules: `app/src/main/python`

## Tech Stack

- Kotlin, AndroidX, Material Components
- Retrofit + OkHttp
- WebSocket (OkHttp + Java-WebSocket in separate layers)
- Chaquopy (embedded Python)
- TensorFlow Lite and model partition support for on-device inference tasks

## Notes

- The worker is implemented as a foreground service with `START_STICKY` behavior.
- Task execution includes queueing, checkpointing, and resume support.
- For protocol specifics and internal flow details, use [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).