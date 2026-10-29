# Security — tracking

Context: dedicated tracking for the app's security testing (static and dynamic), started on
2026-08-29. Auth/HTTP-specific points (Basic Auth, cleartext-HTTP consent,
`EncryptedSharedPreferences`...) stay documented in `CONNECTIVITY_TODO.md` — this file doesn't
duplicate them, it points there and covers the rest (TLS, build/distribution, dependencies).

## Static checks done (2026-08-29)

Quick searches (`grep`) over `app/src/main/java/`:

- ✅ No `Log.*`/`println` prints a password/credentials/Authorization/token.
- ✅ No TLS bypass: no custom `TrustManager`/`HostnameVerifier`, no empty `checkServerTrusted`, no
  permissive `X509TrustManager`. `network_security_config.xml` trusts system + user anchors (so
  TrueNAS's self-signed certificate once manually imported by the user), without disabling
  certificate verification.
- ✅ No real secret hardcoded — only placeholders in Compose `@Preview`s
  (`LoginScreen.kt`, `DashboardScreen.kt`: fake IP/password for the Android Studio preview).
- ✅ `truenas_session.xml` (credentials via `EncryptedSharedPreferences`) properly excluded from
  cloud backup and device transfer (`backup_rules.xml`, `data_extraction_rules.xml`).
- ✅ The TrueNAS API key shared in chat on 2026-08-29 (reused across several sessions the same
  day: reporting diagnosis, then verifying the `alert.*` and `app.*` formats before
  implementation — see `REPORTING_TODO.md`, `ALERTS_TODO.md`, `APPS_TODO.md`) didn't leak into the
  repo. The test server's LAN IP is also absent from the repo (re-read and cleaned up afterward in
  `ALERTS_TODO.md`/`APPS_TODO.md` — never rely solely on the initial read-through for this kind of
  detail, run a dedicated grep before committing).

## Dynamic checks done (2026-08-29, emulator via adb)

In response to a list of 8 security testing axes provided by the user (TLS/HTTP transport,
credential storage, auth/session, input validation, APK/reverse engineering, exposed Android
components, dual local/domain, local cache/storage). For lack of `mitmproxy`/`jadx`/`apktool`
installed on this machine, axes 1 (proxy interception) and 5 (decompilation) could only be
verified statically (see above) — not tested dynamically here, left for the user to do with those
tools. Everything else could be verified live via `adb` on the emulator that was already running
(a debug build installed from an earlier `run` session, with a TrueNAS "Stay logged in" session
still active on it):

- ✅ **Credential storage (axis 2)**: `adb shell run-as ... cat shared_prefs/truenas_session.xml`
  → every key and value is encrypted (Tink/AES-GCM blobs), including the field name itself
  (`AES256_SIV` on the keys) — confirmed no secret appears in the clear even with direct access to
  the app's sandbox (this test was possible here because this debug build is `run-as`-able; on a
  non-debuggable release build, this would require a rooted device, see axis 2).
- ✅ **Logs (axis 2)**: full `adb logcat -d`, filtered on the package name then on
  `password|authorization|basic|token|credential` → no match related to the app (only unrelated
  system WindowManager noise). Dynamically confirms the static grep already done on the code. No
  OkHttp `HttpLoggingInterceptor` in the dependencies either (`NetworkModule.kt`) — no risk of
  request/response logging, even in debug.
- ✅ **Debuggable (axis 5)**: `adb shell dumpsys package` confirms `DEBUGGABLE` on this build —
  normal for a debug build (`installDebug`), not tested here on a real release build for lack of a
  JDK on this machine to produce one (`app/build.gradle.kts` doesn't force `debuggable` anywhere,
  so AGP's default behavior — `false` in release — should apply, but this remains **unconfirmed on
  an actually built release APK**).
- ✅ **Exposed components (axis 6)**: `AndroidManifest.xml` read in full — a single `Activity`
  (`MainActivity`, `exported="true"` only because it's the launcher, normal), no
  `Service`/`BroadcastReceiver`/`ContentProvider`, no deep link/intent filter beyond
  `MAIN`/`LAUNCHER`. Single permission: `INTERNET`. Nothing to fix here.
- ⚠️ **No `FLAG_SECURE` (axis 8)** *(original finding, now fixed)*: confirmed both statically
  (`grep FLAG_SECURE` → no result anywhere in `app/src/main/java`) and dynamically —
  `adb shell screencap` on the foregrounded app (dashboard, session automatically restored via
  "Stay logged in") produced a fully readable image with real data (hostname, CPU model, load,
  memory). Had `FLAG_SECURE` been set on the window, the capture would have been black. So:
  screenshots and the multitasking (recents) preview were **not** blocked on sensitive screens
  (`LoginScreen` with the password field, and more generally any screen showing server data).
  **Fixed on 2026-08-30, dynamically verified working on 2026-10-06** — see the "To do" section
  below.
- ✅ **Restarting the app with "Stay logged in" (axis 3, "reauth after background")**: visually
  confirmed the app relaunches straight to the dashboard (no re-prompt for credentials) — this is
  the intended behavior of "Stay logged in" (`SessionPreferences.isLoggedIn`), not a bug. There's
  no periodic re-validation nor a local biometric/PIN lock before showing data — already documented
  as a known limitation.
- ℹ️ **Network discovery (axis 7)**: confirmed via grep (`NsdManager`/`mdns`/`multicast`/
  `broadcast`) that there is **no automatic discovery** of the server — the user always types an
  address manually (`LoginScreen`/`TrueNasUrl.normalize`). The mDNS-spoofing risk described in
  axis 7 therefore doesn't apply to this app as it exists. Same for "domain→local fallback" —
  there's no automatic switch between two addresses, only one address is used per session, so
  there's nothing to test on that specific point.
- ℹ️ **URL scheme (axis 7, "redirect to a malicious server")**: `TrueNasUrl.normalize` only
  accepts `http://`/`https://` (or defaults to `https://`) — no arbitrary scheme injection
  (`file://`, `content://`...) possible through this field, and OkHttp rejects any non-http(s) URL
  anyway. "Redirect to a malicious server" doesn't really apply here in the classic
  attacker/victim sense: this is a single-user client where the user types their own NAS's address
  themselves on every connection — there's no third party that could inject a URL in their place
  (no deep link, no external intent, see axis 6).
- ✅ **No crash on malformed JSON (axis 4)**: `DashboardRepository.execute()` wraps
  `Gson.fromJson` in the same `runCatching { ... }.toDashboardResult()` as every network call — a
  malformed response (invalid JSON, or a MITM with a wrongly-accepted certificate) surfaces as a
  `Result.failure` handled by the UI (error message), not a crash. Also confirmed there is no `!!`
  (non-null assertion) anywhere in `app/src/main/java` — the most common crash point on unexpected
  server data is therefore already excluded by construction.
  No WebView in the app (everything is native Compose) so no XSS-via-WebView risk (axis 4); no
  local file read/write based on a path returned by TrueNAS either (the app has no
  file/dataset browser), so path traversal doesn't apply.

## mitmproxy interception (2026-08-29, axis 1)

Tool installed by the user (`mitmproxy`/`mitmdump`), an HTTP(S) proxy set up on the emulator
(`adb shell settings put global http_proxy 10.0.2.2:8080`) pointing to an `mitmdump` running on
the host machine. Tested against the user's real TrueNAS (LAN), read-only.

- ✅ **Key result**: reconnecting the app over `https://` on that same address (instead of the
  `http://` already configured) without the emulator trusting mitmproxy's certificate → **connection
  refused**. mitmproxy logs: `Server TLS handshake failed: self-signed certificate` then
  `Client TLS handshake failed. The client does not trust the proxy's certificate ... (certificate
  unknown)`. On the app side: a clear message shown to the user, *"Server certificate not
  trusted. If your TrueNAS uses a self-signed certificate, import it into Android's security
  settings."* — no silent fallback, no crash, and crucially **no HTTP request went out** before the
  TLS handshake failed (so no credentials sent to a spoofed server). Dynamically confirms the
  absence of a TLS bypass already verified statically (no custom `TrustManager`).
- ℹ️ The session already active on the emulator at test time was using `http://` (not HTTPS —
  the "cleartext HTTP to a LAN IP" scenario documented and accepted in `CONNECTIVITY_TODO.md`).
  mitmproxy could therefore read these requests in the clear, and I confirmed the
  `Authorization: Basic ...` header (the user's real TrueNAS credentials, reversible base64
  encoding) does go out on every call, including the dashboard's 2s polls (CPU/Memory/Pools) — not
  a new risk, just a concrete demonstration of the already-documented, knowingly-accepted risk.
- Side effect: this test required logging out of the real TrueNAS session on the emulator (fake
  test credentials `sectest`/`sectest`, never sent since blocked before auth) — the "Stay logged
  in" session with the real credentials is therefore no longer active on it after this test.
  mitmproxy capture files (containing the real `Authorization` header) and the emulator's system
  proxy were reset afterward.

## jadx decompilation (2026-08-29, axis 5)

`jadx` 1.5.6 installed from the official repos (`pacman -S jadx` — the `apk-tools` package the
user had installed beforehand is actually the Alpine package manager, unrelated, not to be
confused with it). APK extracted from the emulator via `adb pull` on the path from
`pm path com.nasmanagerapp` — **this is the debug build already installed**
(`installDebug`), not a real release APK (no JDK on this machine to build one): the findings below
about obfuscation therefore assume nothing new about the future release build
(`optimization.enable = false` already documented above), and the `debuggable=true` found in it is
normal for this kind of build, not a release-config defect.

- ✅ **Visual confirmation of the lack of obfuscation**: the ~55 classes of the
  `com.nasmanagerapp` package (and ~17,700 classes overall, dependencies included) decompile
  with their original class/method/field names intact (`TrueNasAuthRepository`,
  `DashboardViewModel.loadSystemSettings`, `SessionPreferences.KEY_PASSWORD`...) — nothing for an
  attacker to "break", reading it is as easy as the source code.
- ⚠️ **Found: password in the clear in `LoginUiState.toString()`** — `data class LoginUiState`
  (`LoginScreen.kt`) has a `val password: String` field, and Kotlin auto-generates a `toString()`
  that includes every constructor field, so `password=<clear-text value>` would show up in the
  string if this state was ever logged, serialized (crash reporter, State Bundle...), or inspected
  (Layout Inspector). No `Log.*`/serialization of this state exists in the code today (verified),
  so it's not actively exploited — but it's a landmine sitting in the binary as-is, present since
  the very first commit (`db917ff`). **Fixed on 2026-08-29**: `LoginUiState` now has an
  `override fun toString()` that replaces `password` with `***` (equals/hashCode remain
  auto-generated by the `data class`, unchanged — only the display is affected).
- ⚠️ **Found, and already explained by git history: real LAN IP in an `@Preview`** — the
  decompiled file `ComposableSingletons$DashboardScreenKt.java` still contains the user's real
  server LAN IP in the clear. That's exactly the value that commit `3351f01` ("Remove the real IP
  address from the dashboard's Compose preview") replaced with `"192.168.*.*"` in
  `DashboardScreen.kt` — but **the APK currently installed on the emulator predates that commit**
  and has never been rebuilt since. Concretely confirms that fixing the source code doesn't purge
  an already-built/distributed binary: worth keeping in mind if the app is ever shared outside this
  machine (rebuild + reinstall required after such a fix, not just a `git commit`). Not an active
  risk here (nothing distributed), but a good confirmation that the original fix was warranted.
- ℹ️ **Merged manifest (visible only via the APK, not in the source `AndroidManifest.xml`)**:
  decompilation reveals 3 exported components injected by AndroidX libraries, invisible when
  reading the source manifest alone (axis 6):
  - `androidx.compose.ui.tooling.PreviewActivity` (`exported="true"`) — comes from
    `debugImplementation(libs.androidx.compose.ui.tooling)`, so absent from a real release build.
  - `androidx.activity.ComponentActivity` (`exported="true"`) — a standard artifact of the
    `androidx.activity` library itself, present in virtually every Compose app; does nothing
    without a specific Intent contract, no exploitable functionality.
  - `androidx.profileinstaller.ProfileInstallReceiver` (`exported="true"`) — but protected by the
    `android.permission.DUMP` permission (signature/system level), so unreachable by a normal
    third-party app.
  None of the three is specific to this app nor a real risk — standard AndroidX boilerplate — but a
  good reminder that auditing the source manifest alone (axis 6) doesn't show everything the final
  binary exposes.

## Dynamic tests

### Done (2026-08-29, by the user on a real device)

- [x] Log in, kill the app, reopen it → credentials properly restored if "Stay logged in" is
  checked.
- [x] Log out → a new login is correctly required (no memory-dump verification, but correct
  observable behavior).
- [x] Change the password on the TrueNAS side while the app is connected → the next request fails
  cleanly, no crash.
- [x] Attempt an `http://` connection without checking the consent box → properly blocked on the
  `TrueNasAuthRepository.login()` side, not just in the UI.

### To do

- [x] `FLAG_SECURE` on the login screen — fixed on 2026-08-30: `MainActivity.kt` toggles
  `WindowManager.LayoutParams.FLAG_SECURE` based on `isLoggedIn` (set while `LoginScreen` is
  shown, removed once logged in) via a `DisposableEffect`. Decision: only the login screen, not
  the dashboard (the server data shown afterward is judged less sensitive than the credentials
  form). **Verified dynamically on 2026-10-06**: `adb shell screencap`/recents preview are black
  while `LoginScreen` is showing, and readable again once logged in — matches the intended scope.
- [x] Build a real release APK and redo the release-focused checks — done 2026-09-11 (this
  machine now has a working JDK/Gradle toolchain): `./gradlew assembleRelease` succeeds,
  `aapt2 dump xmltree` on the merged manifest shows **no `debuggable` attribute at all** (AGP's
  release default, not forced true) — confirms the assumption from 2026-08-30 was correct.
- [x] Dynamic smoke test of the signed, R8-optimized release build — done and validated
  2026-10-06, see "R8 enabled for release" below.
- [x] Connect over HTTPS with an **unimported** self-signed certificate → confirmed on 2026-08-29
  (see the dynamic section below): clear message, no silent fallback.
- [x] Intercept HTTPS traffic with a proxy (mitmproxy) and a certificate not trusted by
  Android → confirmed on 2026-08-29: request rejected (see the dynamic section below).
- [x] Decompile the current release APK — redone 2026-09-11 on the `assembleRelease` output
  (`unzip` + `strings` on `classes2.dex`, where dexBuilder placed this app's own classes):
  fully readable class/method names in the clear (`SessionPreferences`, `TrueNasAuthRepository`,
  `TrueNasAuthRepository$login$1`...), consistent with `optimization.enable = false` — no change
  from the 2026-08-29 `jadx` finding, just now confirmed on an actual release build instead of a
  debug one.
- [x] Dependency review — redone 2026-09-11 against Google's Maven and Maven Central directly
  (see "Dependency update (2026-09-11)" below): every dependency confirmed at its latest stable
  release, no known-outdated version left. Re-audit periodically (no CVE database was queried
  here, only "is this the latest stable" — a dedicated OWASP Dependency-Check-style scan is still
  a possible follow-up, not done here for lack of the tool on this machine).
- [x] "Update All" with no confirmation — fixed on 2026-08-30: `AppsScreen.kt` now shows an
  `AlertDialog` ("Update all apps?", number of apps affected, Cancel/Update) before calling
  `onUpgradeAll`. Decision: only "Update All" (which relaunches several updates at once), not
  "Dismiss" on an alert nor updating a single app individually — one is trivially reversible (a
  re-dismissable alert has no destructive effect), the other only ever has a single effect at a
  time, unlike the accidental-use risk specific to the bulk action.
- [ ] `DashboardViewModel.awaitJobCompletion` (see `APPS_TODO.md`) has no timeout — a job stuck on
  the server side would have the app poll indefinitely as long as the screen stays open (not a
  data leak, a robustness point, not addressed alongside the confirmation dialog above).
- [ ] Document the "dedicated TrueNAS account for the app, not the admin account" recommendation —
  see `CONNECTIVITY_TODO.md` ("Not to forget..." section) and the README, both added on
  2026-08-30. Still to decide whether it's worth also adding a help text directly in
  `LoginScreen`.

## Static re-check + leak review (2026-09-11)

Fresh pass over `app/src/main/java/` after the recent theme-toggle and app-icon changes, plus a
dedicated leak review (the point that hadn't been covered as its own axis before):

- ✅ Repeated the 2026-08-29 `grep` sweep (password/credential/token/Authorization in
  `Log.*`/`println`, custom `TrustManager`/`HostnameVerifier`, hardcoded secrets, `!!` non-null
  assertions) → still clean, nothing new introduced by the theme/icon work.
- ✅ **`FLAG_SECURE`** still correctly toggled in `MainActivity.kt` after this session's refactor
  (theme state hoisted above it) — the `DisposableEffect(isLoggedIn)` block was untouched by that
  change, verified line-by-line rather than assumed.
- ✅ **New `ThemePreferences`** (`data/theme/ThemePreferences.kt`): plain `SharedPreferences`
  (not `EncryptedSharedPreferences`), correctly so — it only ever stores the `ThemeMode` enum name
  (`SYSTEM`/`LIGHT`/`DARK`), nothing sensitive. Not added to `backup_rules.xml`/
  `data_extraction_rules.xml`'s exclusions, which is correct here (unlike `truenas_session.xml`,
  restoring this one on a new device is harmless and actually the expected UX).
- ✅ **Coroutine/resource leak review** (not done as its own axis before): every `.launch` in
  `DashboardViewModel` runs in `viewModelScope` (auto-cancelled on `onCleared`), no `GlobalScope`
  anywhere in the app. The 2s dashboard poll (`pollingJob`) is stored and explicitly
  `.cancel()`'d before being restarted — no duplicate polling loops accumulating. Every
  `okHttpClient.newCall(request).execute()` (3 call sites: `TrueNasAuthRepository`,
  `DashboardRepository` ×2, `AppsScreen`'s icon fetch) is wrapped in `.use { response -> ... }` —
  response bodies are always closed, no leaked OkHttp connections. Only one `DisposableEffect` in
  the whole app (the `FLAG_SECURE` one above), and it correctly cleans up in `onDispose`. No
  `registerReceiver`/manual listener registration anywhere to forget to unregister — the app is
  pure Compose + `viewModelScope`, no manually-managed lifecycle objects.
- ℹ️ **Noted, not actionable**: `androidx.security:security-crypto` 1.1.0 (`SessionPreferences.kt`)
  now surfaces `-Xlint`-style deprecation warnings on `MasterKey`/`EncryptedSharedPreferences`
  under the newer Kotlin toolchain (see "Dependency update" below) — Google has flagged this API
  surface for eventual replacement, but 1.1.0 is still the latest published version (no successor
  library exists yet on `dl.google.com` as of 2026-09-11), so there is nothing to migrate to today.
  Worth re-checking next time dependencies are refreshed.

## Dependency update (2026-09-11)

Checked every version in `gradle/libs.versions.toml` against `dl.google.com` (AndroidX/Compose/AGP)
and Maven Central (Kotlin, OkHttp, Gson, coroutines, JUnit), stable releases only (pre-release
`alpha`/`beta`/`rc` excluded):

| Dependency | Before | After | Notes |
|---|---|---|---|
| AGP | 9.3.2 | 9.3.2 | see "Reverted" below — latest stable (9.4.0) rejected by the installed Android Studio |
| Kotlin (+ Compose compiler, tied via `version.ref`) | 2.2.10 | 2.4.20 | latest stable |
| Compose BOM | 2026.02.01 | 2026.09.00 | latest stable |
| Gradle wrapper | 9.5.0 | 9.5.0 | see "Reverted" below — was bumped to 9.7.1 to satisfy AGP 9.4.0's minimum, reverted along with it |
| everything else (`coreKtx`, `activityCompose`, `lifecycleRuntimeKtx`/`lifecycleViewmodelCompose`, `okhttp`, `gson`, `coroutines`, `securityCrypto`, `junit`, `junitVersion`, `espressoCore`) | — | unchanged | already the latest stable version available |

Verified after the bump: `./gradlew assembleDebug testDebugUnitTest assembleRelease` all succeed
(no source changes needed — the only new compiler warnings are the pre-existing
`security-crypto` deprecations above and a couple of unrelated pre-existing ones, `ShowChart`
icon and an unnecessary safe call in `DashboardRepository`/`AppsScreen`, none introduced by this
update). No CVE database was queried (none available on this machine) — "latest stable" is the
only guarantee here, not a vulnerability scan.

**Reverted the same day**: AGP 9.4.0 built fine via `./gradlew` (Gradle's own version-check only
requires ≥ 9.6.0, satisfied by the 9.7.1 wrapper bump), but the user's installed Android Studio
refused it at sync/build time — "incompatible version (AGP 9.4.0) ... Latest supported version is
AGP 9.3.0", a separate, stricter Studio-side compatibility table unrelated to Gradle's check and
invisible from the command line. Command-line-only verification isn't sufficient for the AGP/Kotlin
Gradle-plugin versions specifically — they need confirmation that the *IDE* accepts them too, not
just `gradlew`. Reverted `agp` to `9.3.2` and the Gradle wrapper to `9.5.0` (both back to their
exact pre-update values); Kotlin 2.4.20 and Compose BOM 2026.09.00 were kept since Studio's
complaint was specifically about the AGP plugin version, not those. Re-verified:
`assembleDebug`/`testDebugUnitTest`/`assembleRelease` all still succeed at 9.3.2/9.5.0.

## Static re-check after the package rename + UI text changes (2026-10-04)

Fresh pass over `app/src/main/java/` after renaming the package from `com.nasmanagerapp`
to `com.nasmanagerapp` (`applicationId`/`namespace` in `app/build.gradle.kts` updated accordingly)
and after the "NasManager mobile" / "Compatible with TrueNAS Scale" text additions in
`LoginScreen.kt`, `DashboardScreen.kt` and `AppDrawer.kt`:

- ✅ Repeated the usual grep sweep (password/credential/token/Authorization in
  `Log.*`/`println`, custom `TrustManager`/`HostnameVerifier`, hardcoded secrets/real IPs, `!!`
  non-null assertions) → still clean, nothing introduced by the rename or the UI text changes
  (plain `Text(...)` literals only, no new data path).
- ✅ `FLAG_SECURE` toggle in `MainActivity.kt` untouched by the rename — only the package line and
  imports changed, the `DisposableEffect(isLoggedIn)` logic is identical.
- ✅ `SessionPreferences`/`CredentialsStore`/`AuthInterceptor`/`TrueNasAuthRepository` re-read in
  full after the rename — `EncryptedSharedPreferences` usage, Basic Auth header construction and
  the `acceptHttpRisks` gate are all unchanged, only the `package`/`import` lines moved.
- ✅ `network_security_config.xml`, `backup_rules.xml`, `data_extraction_rules.xml`,
  `AndroidManifest.xml`: content unaffected by the rename (none of them hardcode the old package
  name), re-read to confirm.
- ✅ `app/src/test`, `app/src/androidTest`: no hardcoded password/secret/API key.
- ✅ `./gradlew compileDebugKotlin testDebugUnitTest` green after the rename and the UI text
  changes.
- ℹ️ **Not a security issue, just a leftover naming inconsistency**: `Theme.MyTrueNasScale` /
  `MyTrueNasScaleTheme` (`themes.xml`, `ui/theme/Theme.kt`) and `app_name` in `strings.xml` still
  say `myTrueNasScale` — purely cosmetic, not in scope of this security check, flagged here so it
  isn't mistaken for a rename that was missed.
  ✅ Since resolved: the theme is `Theme.NasManagerApp` and `app_name` is `NasManager mobile`
  (checked 2026-10-06, while preparing the F-Droid listing — which uses "NasManager" as its title,
  not "TrueNAS", a registered trademark).
- Nothing new found beyond what's already tracked below ("Points of attention") and in
  `CONNECTIVITY_TODO.md` — no change in those risk acceptances from this pass.

## R8 enabled for release, in preparation for publishing (2026-10-04)

Context: the app is now intended to be actually published, so the `optimization.enable = false`
point flagged above was fixed rather than just tracked.

- **`app/build.gradle.kts`**: `buildTypes.release.optimization.enable` switched to `true`, with
  `keepRules { files.add(file("proguard-rules.pro")) }`. `includeDefault` is left at its default
  (`true`), which already pulls in AGP's standard `proguard-android-optimize.txt` — no need to
  reference it manually with this newer DSL (unlike the legacy `getDefaultProguardFile(...)` API).
- **New `app/proguard-rules.pro`**, two concerns specific to this app:
  - **Gson DTOs** (`DashboardRepository.kt`): Gson's own bundled rules
    (`META-INF/proguard/gson.pro`, automatically applied) only protect fields annotated with
    `@SerializedName` — most DTO fields here aren't (only the snake_case ones are). Without an
    explicit keep, R8 would have silently renamed the rest and broken JSON (de)serialization
    (fields reading as `null` instead of crashing — exactly the kind of regression that's easy to
    miss without a release-build smoke test). Added
    `-keep class com.nasmanagerapp.data.dashboard.*Dto { *; }` and `*Request { *; }`, matching the
    naming convention already used for every DTO/request class in that file. **If a future DTO or
    request class doesn't follow this `*Dto`/`*Request` naming convention, it must be added here
    explicitly.**
  - **Tink** (`com.google.crypto.tink.**`, pulled in by `androidx.security-crypto` for
    `EncryptedSharedPreferences`): known to resolve some crypto primitives by class name at
    runtime; shrinking/renaming those classes is a documented cause of crashes on first launch in
    other Android apps using this library. Kept wholesale (`-keep` + `-dontwarn`) rather than
    risking a crash on the exact file that stores the user's credentials.
- **Verified, not just assumed**: built `assembleRelease` and inspected `classes.dex` directly
  (`dexdump`) rather than trusting the build log alone —
  - App's own business-logic classes (`TrueNasAuthRepository`, `DashboardViewModel`,
    `SessionPreferences`, `DashboardRepository`...) are confirmed **renamed/obfuscated** (zero
    matches by original name in the dex) — this was the actual goal (the 2026-08-29 `jadx` finding
    above, readable class/method names in the clear, is now fixed for a real release build.
  - All ~30 `*Dto`/`*Request` classes are confirmed **present verbatim** in the dex (checked by
    name, e.g. `SystemInfoDto`, `PoolDto`, `AlertDto`, `JobDto`...) — the keep rule works as
    intended.
  - `com.google.crypto.tink.*` classes confirmed present in the dex (thousands of matches) — not
    stripped.
  - `MainActivity`/`TrueNasApplication` still have their original names — expected and harmless,
    AGP's default rules always keep Android components referenced from the manifest.
  - Resource shrinking also kicked in automatically as part of the same `optimization` block (no
    separate flag needed with this DSL) — `optimizeReleaseResources`/
    `convertShrunkResourcesToBinaryRelease` both ran.
  - `./gradlew compileDebugKotlin testDebugUnitTest assembleRelease` all green after this change.
- ✅ **Done and validated 2026-10-06**: dynamic smoke test of the signed release build on a
  device/emulator (login, dashboard polling, reporting graphs, apps, alerts, system screen) against
  a live TrueNAS server — passed, no R8/Gson regression found — compiling and the dex-level keep-rule check above are not a substitute for
  actually exercising Gson (de)serialization end-to-end on this specific build type, so this had
  to be run separately.
- ℹ️ Build emits one forward-looking deprecation warning: `'val files: SetProperty<File>' is
  deprecated. Use keepRules source folder instead.` No such folder-based API actually exists yet in
  AGP 9.3.2 or 9.4.0 (checked both `KeepRules` DSL class files directly) — `files.add(...)` is the
  only working option today. Harmless, just something to revisit on a future AGP upgrade.
- ✅ **Signing — done 2026-10-06**: `app/build.gradle.kts` now declares a `signingConfigs.create("release")`,
  built only if `RELEASE_STORE_FILE` is set, reading `storeFile`/`storePassword`/`keyAlias`/
  `keyPassword` via `providers.gradleProperty(...)` (`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`,
  `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) — none of these four values, nor the keystore file
  itself, are committed to the repo; they live in the signer's own `~/.gradle/gradle.properties`
  (or CI secrets), outside this project entirely. `buildTypes.release` wires this config in only
  when it exists (`signingConfigs.findByName("release")?.let { signingConfig = it }`), so
  `assembleRelease` still falls back to an unsigned APK with no configuration failure for anyone
  without those properties set — confirmed both ways. The keystore file and its four passwords are
  backed up and rotated outside this repo; see `README.md` for the build-time convention (env/property
  names), not for where that backup lives.

## App icons: SVG support + bounded download/decode (2026-10-08)

Review of the SVG-icon change (`AppsScreen.kt`, see `APPS_TODO.md`), which added AndroidSVG
(`com.caverock:androidsvg-aar:1.4`).

- Icon URLs come from the NAS (`metadata.icon`) — rewritable on the wire over `http://`, arbitrary
  for a custom app — so the fetch is now bounded: body capped at 1 MiB (`readAtMost`,
  `MAX_ICON_BYTES`; real catalog icons are ≤ ~80 KB), raster icons decoded subsampled to about the
  displayed 40dp from the screen density (`iconSampleSize`, longer side, so an extreme aspect ratio
  can't bypass it), SVGs rendered into a fixed-size bitmap. Before, a large file or a
  "decompression bomb" PNG could exhaust memory (pre-existing for PNG). Unit-tested.
- AndroidSVG: no permission/manifest entry added; external XML entities explicitly disabled in its
  parser (no XXE); no `SVGExternalFileResolver` registered (an SVG can't trigger other fetches);
  R8 release build OK. Still `imageOkHttpClient` (no credentials).
- `.env` (local test API key) added to `.gitignore`; key checked absent from git history, the diff
  and `build/`.

Still open:
- [x] AndroidSVG enables **internal** XML entities by default (`<!ENTITY ...>` in the SVG's DTD,
  "billion laughs" expansion risk) — fixed: `SVG.setInternalEntitiesEnabled(false)` in
  `TrueNasApplication.onCreate` (global setting, covers any future AndroidSVG use). Checked on the
  emulator with a harmless 10³ trap SVG (entity in `<title>`): library default → title expanded to
  3000 chars; with the app setting → 0, no error, and the Immich icon still renders.
- [ ] Icon fetch accepts any scheme, `http://` included — restrict to `https://` (the TrueNAS CDN
  is HTTPS)?
- [ ] AndroidSVG's last release is 1.4 (2019), effectively unmaintained — no known CVE, but no CVE
  database was queried; re-check at the next dependency audit.

## Points of attention

- ~~**`app/build.gradle.kts`**: `buildTypes.release.optimization.enable = false`~~ — fixed
  2026-10-04, see "R8 enabled for release" above.
- See `CONNECTIVITY_TODO.md`, "Not to forget to remove/revisit before production" section, for
  points already tracked on the auth/HTTP side (TrueNAS password potentially shared with the web
  admin account, no revocation possible from the app, etc.).
