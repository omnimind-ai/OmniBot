# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OmnibotApp is an AI-powered intelligent robot assistant application for Android. It's a hybrid app combining native Android Kotlin code with Flutter UI, implementing a modular architecture with clear separation of concerns.

**Key characteristics:**
- Android app with embedded Flutter UI module
- Modular monorepo architecture with feature-specific modules
- State machine-based task management system
- Floating overlay and background task functionality
- Agent tools, including optional Shizuku-backed privileged Android actions

## Build and Development Commands

### Android/Gradle Commands
```bash
# Full project build
./gradlew build

# Build debug APK (develop flavor)
./gradlew :app:assembleDevelopStandardDebug

# Build release APK (production flavor)
./gradlew :app:assembleProductionStandardRelease

# Run tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest

# Lint checking
./gradlew lint

# Install debug APK to connected device
./gradlew :app:installDevelopStandardDebug
```

### Flutter Commands (for ui/ module)
```bash
cd ui

# Install dependencies
flutter pub get

# If you encounter "Could not read script '.../ui/.android/include_flutter.groovy'" error:
flutter clean
flutter pub get

# Build Flutter module as AAR
flutter build aar

# Run Flutter tests
flutter test

# Analyze Flutter code
flutter analyze
```

## Architecture Overview

### Module Structure
```
OmnibotApp/
├── app/                 # Main application module (entry point, activities)
├── ui/                  # Flutter UI module (cross-platform UI with Riverpod)
├── baselib/             # Core libraries (database, networking, auth, storage)
├── assists/             # Chat/task coordination and shared HTTP integration
├── overlay/             # Floating overlay functionality
└── testbot/             # Testing utilities (develop flavor only)
```

### Core Architectural Patterns

**1. State Machine Pattern** (`assists/StateMachine.kt`)
- Coordinates the remaining chat/task lifecycle
- Manages communication between UI, services, and background work

**2. Flutter-Native Embedding**
- Flutter module embedded in native Android app via `FlutterEngineGroup`
- Communication channels between Kotlin and Flutter
- Shared resource management across Flutter engine instances

**3. Task-Based System**
- Agent and subagent task execution
- App-level scheduled tasks for deferred subagent runs
- Background execution with Kotlin coroutines and Android alarms

**4. Service-Oriented Architecture**
- Overlay services for floating UI elements
- Background services for long-running tasks
- Shizuku-backed privileged shell actions when the user authorizes them

### Key Integration Points

**Assists Module** (`assists/`)
- `StateMachine.kt`: Core state machine managing task lifecycles
- `AssistsCore.kt`: SDK interface for task creation, state changes, and results

Directory structure:
- `api/`: Models, enums, listeners
- `controller/`: HTTP and shared task controllers
- `task/`: Remaining chat/task implementations
- `util/`: Utility classes

**Database Layer** (`baselib/`)
- Room database with DAOs for conversations and messages
- MMKV for lightweight key-value storage
- Located in `baselib/src/main/java/cn/com/omnimind/baselib/database/`

**Flutter UI** (`ui/`)
- Riverpod for state management
- Go Router for navigation
- Material Design 3 components
- Embedded as AAR module in native app

## Build Flavors

The project uses product flavors for different environments:

**develop**: Development environment
- Optional backend via `OMNIBOT_BASE_URL` (empty by default in open-source mode)
- Includes testbot module
- Debug signing config (Android default debug keystore)

**production**: Production environment
- Optional backend via `OMNIBOT_BASE_URL` (empty by default in open-source mode)
- Excludes testbot module
- Release signing config with V2/V3 signatures

## Configuration

Optional/required properties in `gradle.properties` or `~/.gradle/gradle.properties`:

```properties
# Optional backend endpoint for self-hosted deployments
OMNIBOT_BASE_URL=

# Required only for release signing
OMNI_RELEASE_STORE_FILE=/abs/path/release.jks
OMNI_RELEASE_STORE_PWD=***
OMNI_RELEASE_KEY_ALIAS=***
OMNI_RELEASE_KEY_PWD=***
```

## Development Notes

### Platform Requirements
- **Min SDK**: 30 (Android 11)
- **Target SDK**: 34 (Android 14)
- **Compile SDK**: 36
- **NDK**: ARMv7 and ARM64 architectures
- **JDK**: 11+
- **Flutter**: 3.9.2+
- **Kotlin**: Latest (via Gradle plugin)

### Module Dependencies
- All modules except `app` are Android library modules
- Flutter integration via `include_flutter.groovy`
- `testbot` only included in develop flavor

### State Management
- **Native (Kotlin)**: Coroutines, Flow, and custom state machine
- **Flutter**: Riverpod with code generation (riverpod_annotation)
- **Database**: Room with Flow-based observables

### Permissions
- System overlay permission (for floating UI)
- Optional Shizuku authorization for privileged Android actions
- Standard Android permissions as needed

### Key Files to Understand
- `app/src/main/java/cn/com/omnimind/bot/App.kt`: Application entry point with MCP integration
- `assists/src/main/java/cn/com/omnimind/assists/StateMachine.kt`: Task state machine
- `assists/src/main/java/cn/com/omnimind/assists/AssistsCore.kt`: Task SDK interface
- `baselib/src/main/java/cn/com/omnimind/baselib/database/`: Database layer

## Version Management

The app includes automatic version update checking and forced update functionality. Version info is in `app/build.gradle.kts`:
- `versionCode`: 14
- `versionName`: "1.6.1"

## External Integrations

- **WeChat Login**: Social authentication
- **ML Kit**: OCR capabilities
- **MCP Server**: Model Context Protocol integration (see `McpServerManager`)
