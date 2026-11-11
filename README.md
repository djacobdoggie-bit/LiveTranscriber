# Live Transcriber (Android)

Offline, real-time speech-to-text. Tap **Start**, it transcribes what the mic hears live; tap **Stop**, it saves a timestamped `.txt` file to `Downloads/LiveTranscriber` on your phone.

## Get the APK (GitHub builds it for free)

1. On github.com, create a new repository (e.g. `LiveTranscriber`).
2. On the empty repo page, click **uploading an existing file**, drag in **everything inside** this folder (including the `.github` folder), and click **Commit changes**.
   - If the `.github` folder didn't upload: **Add file → Create new file**, name it `.github/workflows/build-apk.yml`, paste in the contents of that file, and commit.
3. Open the **Actions** tab. A "Build APK" run starts automatically and takes about 3–5 minutes.
4. When it shows a green check, open **Releases → Live Transcriber (latest build)** on your phone and download **LiveTranscriber.apk**.

## Install on your phone

Open the downloaded APK. Android will ask you to allow installs from your browser or Files app — allow it, then tap **Install**. (Play Protect may warn that the app is unknown; choose to install anyway.)

## Make it work offline (one-time)

The app uses your phone's own speech engine. Download the offline language once while you have internet:

- **Settings → search "offline speech" or "voice typing"**, or open **Gboard settings → Voice typing → Offline speech recognition**, then download **Filipino** (and **English** if you want).
- On newer phones the app also asks Android to download the language automatically the first time.
- Test it with **Airplane mode on**.

## Notes

- Keep the app open while recording; the screen stays on automatically.
- The phone's engine pauses briefly after long silences and restarts by itself, so a word can occasionally be missed at the very start of a sentence.
- Files are in **Downloads/LiveTranscriber**; the **Share** button sends the latest one.
