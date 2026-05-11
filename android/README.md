# SJTU Agent Android

Native Android client for SJTU Agent. It does not run the Python backend; the Python codebase is used as the protocol and behavior reference.

## Stack

- Kotlin
- Jetpack Compose + Material 3
- OkHttp
- Kotlinx Serialization
- AndroidX Security Crypto for local encrypted credential storage
- WebView-based login session capture for cookie-based campus systems

## Open Locally

1. Open the `android/` directory in Android Studio.
2. Let Gradle sync install the Android Gradle Plugin and dependencies.
3. Run the `app` configuration on an Android device or emulator.

CLI build, once JDK and Android SDK are installed:

```powershell
cd android
gradle test
gradle assembleDebug
```

## Current Scope

Implemented:

- Bottom navigation: Today, DDL, Chat, Schedule, Profile.
- Encrypted local storage for API keys, Canvas Token, cookies, settings, and reminders.
- WebView login capture for AI Haoke, JWXT, Phycai, MOOC, Shuiyuan, and Dyweb.
- Native clients for Canvas, AI Haoke, MOOC, JWXT schedule/grades, Phycai, JWC/Shuiyuan/Dyweb search.
- OpenAI-compatible chat completions with local tool calls.
- Parser unit tests for week parsing, class slots, and MOOC RPC deadline parsing.

Reserved for a later slice:

- FCM push delivery.
- WorkManager/AlarmManager notification scheduling.
- File upload UI for Canvas submissions.
- Full WebView fallback flows for pages that require manual in-page navigation after login.
