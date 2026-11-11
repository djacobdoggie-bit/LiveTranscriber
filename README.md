# Live Transcriber (Android)

Offline, real-time speech-to-text with **Whisper built into the app** (Tagalog, Taglish, English). No language packs or internet needed. Tap **Start**, it transcribes each phrase as people speak; tap **Stop**, it saves a timestamped `.txt` file to `Downloads/LiveTranscriber` on your phone.

The GitHub build downloads the Whisper "small" model and the sherpa-onnx engine and packs them into the APK (~400 MB).

## Get the APK (GitHub builds it for free)

1. On github.com, create a new repository (e.g. `LiveTranscriber`).
2. On the empty repo page, click **uploading an existing file**, drag in **everything inside** this folder (including the `.github` folder), and click **Commit changes**.
   - If the `.github` folder didn't upload: **Add file → Create new file**, name it `.github/workflows/build-apk.yml`, paste in the contents of that file, and commit.
3. Open the **Actions** tab. A "Build APK" run starts automatically and takes about 3–5 minutes.
4. When it shows a green check, open **Releases → Live Transcriber (latest build)** on your phone and download **LiveTranscriber.apk**.

## Install on your phone

Open the downloaded APK. Android will ask you to allow installs from your browser or Files app — allow it, then tap **Install**. (Play Protect may warn that the app is unknown; choose to install anyway.)

## Notes

- Keep the app open while recording; the screen stays on automatically.
- Text appears a phrase at a time, 1–3 seconds after each pause.
- The first launch takes a few seconds to load the model.
- Files are in **Downloads/LiveTranscriber**; the **Share** button sends the latest one.
