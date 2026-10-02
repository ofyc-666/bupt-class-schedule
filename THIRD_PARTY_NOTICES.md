# Third-Party Notices

The BUPT mobile academic-affairs integration in this repository is adapted from:

- Project: `Nemoyuzx/where_to_study`
- Source: https://github.com/Nemoyuzx/where_to_study
- Referenced commit: `4a1a9ae5b6cc3ff5a25046a04102ec052c4c7e50`
- Copyright: Nemoyuzx and where_to_study contributors
- License: GNU General Public License v3.0 (`GPL-3.0-only`)

The following local implementations are adapted from or equivalent ports of upstream logic:

- `data/remote/SjdApiClient.kt` ← `ScheduleClient.kt` transport, redirect, timeout, response-size, login and token logic
- `data/remote/SjdScheduleClient.kt` ← `ScheduleClient.kt` schedule fetch and parser logic
- `data/local/SecureCredentialStore.kt` ← `SecureCredentialStore.kt` Android Keystore AES-GCM storage
- `data/local/ScheduleStore.kt` ← `ScheduleStore.kt` JSON codec and `AtomicFile` cache
- `domain/logic/ScheduleLogic.kt` ← `AppModels.kt` teaching-week calculation and filtering
- `domain/logic/SemesterLogic.kt` ← `SemesterLogic.kt` fallback term suggestion and cache validation
- `data/remote/UCloudAssignmentClient.kt` ← `native/android/app/src/main/java/com/nemoyu/wheretostudy/nativeapp/UCloudAssignmentClient.kt` UCloud CAS, OAuth, and API communication, further adapted for this Android app

`server-reference/src/ucloud/client.ts` is this project's platform-independent TypeScript implementation of the UCloud protocol. Its CAS, OAuth, and API communication follows the same upstream source chain. Later project features, including quiz support, partial sync, and pagination, are local extensions and are not attributed to the upstream implementation.

The full GPL-3.0 license text is included in [`LICENSE`](LICENSE). Local adaptations are marked with SPDX and source comments. No upstream credentials or production response data are included.
