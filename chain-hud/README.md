# Torn Chain HUD for Rokid Glasses — first hardware-test build

This folder is a **new, self-contained Android phone companion app**. It does not replace the older experiment in this repository and does not modify the live Tornbot.

## What it does

- Reads your faction's current chain via Torn API v2: `GET /v2/faction/{id}/chain`
- Draws `CHAIN NNN`, `MM:SS`, and a textual warning in a green-only Rokid CustomView, positioned at the top right (alignment subject to firmware testing)
- Runs a 1-second local display tick and fetches new API data every 20s, or every 10s near expiry; sends `timestamp` to avoid reusing identical service-cache requests
- Foreground connected-device service and low-priority persistent phone notification, so the phone UI can be left
- Stores the Torn key using Android Keystore AES-GCM. Key only sent to official Torn via `Authorization: ApiKey`, never checked into GitHub, never sent to Rokid
- Allows a manual **compatibility refresh** switch for devices that acknowledge `customViewUpdate` but don't visually refresh. This mode CLOSES and REOPENS the HUD every second and may flicker; disable it if updates work.
- Shows `STALE` instead of an untrustworthy deadline if fresh data hasn't arrived for 45 seconds. A countdown hitting zero is labeled "VERIFY IN TORN": the API and glasses are not an authoritative hit confirmation.

## Install the first test build

1. Open GitHub Actions in this repo. Run **Build Torn Chain HUD APK** on the feature branch or get the APK from its PR build artifacts.
2. Download `torn-chain-hud-debug-apk` artifact and install its `app-debug.apk` on your **Android phone**. **Do not install it on the glasses.**
3. Open Global **Hi Rokid**, pair your green-waveguide Rokid Glasses, and sign in.
4. Launch **Torn Chain HUD** on the phone, allow Nearby Devices and Notifications, and tap **START**.
5. Enter your Torn read-only/minimal faction-chain API key *locally* in the phone app; default faction is Damage Inc `20747`.
6. Approve Rokid's authorization dialog. HUD display should appear in the glasses. Look for `HUD open on glasses` as app status.
7. If digits show initially but never change, stop and enable **Compatibility refresh**, then start again. Expect flicker; report the firmware and whether updates work without it.
8. To stop, tap **STOP HUD**. To remove locally stored credentials, tap **ERASE STORED API KEY**.

The phone must have Internet and a Hi Rokid connection. The foreground service can keep trying in the background, but Rokid may pause or replace a CustomView when another glasses scene starts. **Persistent upper-right pinning across other Rokid apps is unverified and may not be possible with CXR-L CustomView alone.** If firmware gives only an exclusive scene, phase 2 is a glasses-side CXR-S overlay with the phone acting as the data source.

## Developer build

Android Studio: open `chain-hud/`, sync Gradle, select `app` > `assembleDebug`. Requirements: Java 17, Android SDK 35, Internet for Android and Rokid Maven repositories. No API credentials at build time. CI uses Gradle 8.11.1, AGP 8.9.2, Kotlin 2.0.21, `client-l:1.0.4`.

On Windows with Gradle installed:

```powershell
gradle -p chain-hud testDebugUnitTest assembleDebug
adb install -r chain-hud/app/build/outputs/apk/debug/app-debug.apk
```

## Engineering notes and limitations

- Tested in source/unit tests only until GitHub Actions and real Rokid hardware report success. Connection callbacks, Bluetooth grants and scene behavior require physical testing.
- No attack automation, target distribution, Tornbot deployment, polling of non-chain endpoints, or shell permissions.
- Torn's cache can be up to 30 seconds even if you poll more often; API updates should never be treated as a substitute for checking Torn before a tight deadline.
- Key is not shared with Tornbot. A server proxy/optional reuse of existing Tornbot verified member auth can be added later, but we intentionally kept this first build independent of production Tornbot.
- Built-in Android networking, `HttpsURLConnection`, and the official Rokid phone SDK; no new backend is required.

## Safety/privacy

The app uses only the saved faction ID and local Torn API key, and sends requests to `https://api.torn.com` over TLS. Data is displayed locally on phone/glasses. No analytics, advertisement, third-party user database or public endpoints. The app does not upload or distribute your API key. An API key is still sensitive; use a narrow-scope read-only key.

## Test criteria

1. Installs on phone and successfully authorizes Hi Rokid.
2. A green-on-transparent 3-line block appears near the top right of the waveguide.
3. Timer ticks every second WITHOUT repeatedly reopening the whole scene in normal mode.
4. Chain count refreshes after another member completes an attack.
5. Under 60/30/15 seconds, warning changes to stronger text; there is no color change on the green-only display.
6. Phone screen off: check 5–10 minutes for service and overlay persistence.
7. Another Rokid app/AI trigger: observe whether the HUD survives, pauses, or disappears.
8. Network disconnect >45s: timer becomes STALE and does not give a false deadline.
9. Revoked key: stop retrying and ask for a valid one; no infinite invalid-key request loops.

## v0.2 — 403 diagnostic and retry fix

Adds an explicit User-Agent because Torn's API docs warn Cloudflare may block default User-Agents. Reads a small HTTP error body fragment to distinguish Torn API error codes, Cloudflare/security HTML pages, or generic HTTP 403; never echoes the raw response or API key. Plain 403 does not disable the collector: it backs off for 60 seconds and retries. Update in place to keep the encrypted key. If it still shows 403, share the **exact message and optional Cloudflare Ray**, not your key. Try switching between Wi-Fi and mobile data.
