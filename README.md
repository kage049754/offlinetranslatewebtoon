# Webtoon Translate

Live, on-device translation overlay for webtoon/manhwa/manga readers.

## What it does
- A floating bubble sits on top of any app.
- Tap it: captures the screen, auto-detects the script/language of any text on
  it (Latin, Korean, Japanese, Chinese, Devanagari), translates every text
  block on-device (Google ML Kit), and draws the translation **directly over**
  each original text box (color-matched patch + auto-sized text), not below it.
- Long-press the bubble to clear the current translation and go back to
  scrolling normally.
- The translation layer never intercepts touches (`FLAG_NOT_TOUCHABLE`), so
  scrolling/tapping the app underneath works immediately after a scan --
  that's the fix for the "can't touch anything but the notification panel and
  back button" bug.

## Build it
This repo has no checked-in Gradle wrapper jar (it was assembled outside
Android Studio). The GitHub Actions workflow installs Gradle 8.7 directly via
`gradle/actions/setup-gradle`, so no local setup is needed:

1. Push this repo to GitHub.
2. Open the **Actions** tab -- a build starts automatically.
3. When it's green, open the run and download `webtoon-translate-debug-apk`
   from **Artifacts**.
4. Install the APK on your phone (allow "install unknown apps" if prompted).

## Permissions it will ask for
| Permission | Why |
|---|---|
| Display over other apps | Shows the floating bubble + translation layer |
| Notifications | Required by Android for the foreground service while translating |
| (Screen capture, one-time per session) | Lets it read what's on screen to OCR it |

## Data persistence
Your chosen target language is saved with Jetpack DataStore, so it survives
the app being force-closed or the phone restarting.

## Known limitations
- App icon uses Android's adaptive-icon format (API 26+); on the very few
  remaining Android 7.0/7.1 devices it'll show a generic icon instead.
- OCR runs 5 script recognizers per scan and keeps the best match -- this is
  what makes "auto-detect" work without you picking a source language, but it
  costs a bit of time per scan (roughly a second or two on a modern phone).
- Translation quality is ML Kit's on-device model -- solid for plot/dialogue,
  but idiom-heavy slang won't be perfect.
