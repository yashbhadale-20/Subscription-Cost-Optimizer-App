# Subscription Optimizer — Android app

Jetpack Compose (Material 3) client for the FastAPI backend in `../backend`.

## Status

| Area | State |
|---|---|
| UI, navigation, validation, theming (light/dark) | Implemented |
| Settings: backend URL, user ID, theme (DataStore) | Implemented |
| Session (`POST /auth/session`), token per backend URL + user ID | Connected |
| Connection test (`GET /health`, `GET /health/ollama`) | Connected |
| Plan comparison (`POST /compare`) with policy evidence | Connected |
| History (`/history`): saved on the server, survives app restarts, reopens full results | Connected |
| Execution trace screen (`GET /history/{id}/trace`) | Connected |
| AI assistant (`POST /chat`) with evidence; saved conversations (`/conversations`) | Connected |
| Policy questions (`POST /policy/ask`, `GET /policy/status`) | Connected |
| Default comparison priority (`/preferences`), server profile (`/memory`) | Connected |

The first user-scoped call asks the backend for a session token for the configured user ID and
stores it (DataStore `sessions`, excluded from backups). A 401 drops the token and requests one new
session before retrying once. A 409 means another device registered that user ID; the app reports
it and asks you to choose another ID in Settings.

Networking uses Retrofit + OkHttp + kotlinx.serialization (`data/remote`). DTOs in `ApiModels.kt` mirror
`backend/models.py` field for field. `BackendClient` turns every failure into a `RepositoryError`
(unreachable, timeout, Ollama unavailable, rejected, server error, malformed response, not configured),
and the UI shows its title and message. There are no fallback or fake responses: if the backend fails,
the user sees the error and the form keeps everything they entered.

`preview/PreviewFixtures.kt` is used only by Android Studio `@Preview` functions; no runtime code reads it.

## Build

Requirements: Android Studio (Ladybug or newer) or the Android SDK with platform 35, plus JDK 17 or 21.
Gradle can't run lint on JDK 24+, so set `JAVA_HOME` to JDK 17/21. Recent Android Studio versions bundle
JDK 25 as `jbr`; with that JDK, `assembleRelease` fails in `lintVitalAnalyzeRelease`.

```powershell
cd android
.\gradlew.bat assembleDebug        # APK in appuild\outputspk\debug.\gradlew.bat testDebugUnitTest    # validation, network layer (MockWebServer) and ViewModel tests
```

## Connecting to the backend

Start the backend so other devices can reach it:

```powershell
uvicorn backend.main:app --host 0.0.0.0 --port 8000
```

Then set the URL in **Settings** and tap **Test connection**:

| Where the app runs | Backend URL |
|---|---|
| Android emulator | `http://10.0.2.2:8000` (the debug default; 10.0.2.2 is the emulator's alias for the host) |
| Physical phone on the same Wi-Fi | `http://<computer's LAN IP>:8000`, e.g. `http://192.168.1.20:8000` (`ipconfig` shows it) |

`localhost` on Android is the phone or emulator itself, not your computer.

For a physical phone, Windows Firewall must allow inbound TCP 8000. On a network marked **Public**
it usually doesn't. Either switch the Wi-Fi network to Private or add a rule (in an admin shell), and
remove it when you're done:

```powershell
New-NetFirewallRule -DisplayName "Subscription Optimizer dev API" -Direction Inbound -Protocol TCP -LocalPort 8000 -Action Allow -Profile Private
```

Only port 8000 (FastAPI) should be reachable from the network. Ollama must stay on `127.0.0.1:11434`,
which is its default. Don't set `OLLAMA_HOST=0.0.0.0` or open port 11434 in the firewall; the backend
talks to Ollama locally.

LLM-backed calls (`/compare`, `/chat`) wait up to 270 s, matching the backend's 180 s model timeout plus
retrieval. Health and profile calls give up after 15 s.

### Development vs production configuration

| | Debug build | Release build |
|---|---|---|
| Network security config | `src/debug/res/xml/network_security_config.xml`, cleartext allowed | `src/main/res/xml/network_security_config.xml`, HTTPS only |
| Default backend URL | `http://10.0.2.2:8000` | `-PreleaseBackendUrl=https://…` at build time (must be HTTPS), otherwise empty |
| `http://` URLs in Settings | Accepted | Rejected |
| HTTP logging | Method, URL, status, timing (no bodies) | Off |

Before a production deployment, put the backend behind HTTPS (a reverse proxy with a real certificate)
and add authentication. The backend currently has none, and the app sends no credentials.

## Structure

```
app/src/main/java/com/subscriptionoptimizer/app/
├── AppContainer.kt            manual DI and the ViewModel factory helper
├── data/model                 domain models used by the UI
├── data/remote                Retrofit API, wire DTOs (mirror backend/models.py), mappers, error mapping
├── data/repository            repository interfaces and HTTP / in-memory implementations
├── data/settings              DataStore-backed settings
├── domain                     pure Kotlin: validation, formatting, pros/cons derivation
├── preview                    PREVIEW-ONLY sample fixtures
└── ui/
    ├── designsystem           theme (colors, type, shapes, spacing) and shared components
    ├── navigation             routes, bottom bar / navigation rail shell
    ├── home, compare, results, assistant, history, settings   (screen + ViewModel each)
    └── common                 composables shared by several screens
```
