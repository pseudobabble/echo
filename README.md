# Echo

Offline-first Android voice notes with local audio retention and queued on-device transcription.

## v1 architecture

Audio is recorded to app-private storage, indexed in Room, transcribed by a foreground `WorkManager` job, and copied into a user-selected Google Drive folder by independently retryable jobs. The recording notification supplies the lock-screen entry point.

## whisper.cpp integration

The app deliberately has no cloud-transcription fallback. It pins `whisper.cpp` v1.9.5 as a Git submodule in `app/src/main/cpp/whisper.cpp`, builds it through the Android NDK, and calls it through the `echo_whisper` JNI library. Android decodes the recorded AAC audio into the 16 kHz float PCM that whisper.cpp consumes.

The selected model is downloaded after install over Wi-Fi, never packaged in the APK. Echo verifies both its expected size and SHA-256 before atomically installing it. Clone the repository with `--recurse-submodules` before building locally.

## Google Drive v1

Tap **Drive**, select a folder supplied by the Google Drive app, and grant write access. Android persists this folder grant; the worker writes the `.m4a` audio and matching `.txt` transcript after local transcription. This uses the phone's existing Google Drive account and needs no embedded credentials, OAuth client, or Google Cloud configuration. S3 is deliberately deferred.

## Model decision

V1 uses the multilingual `ggml-base-q5_1.bin` whisper.cpp model (~57 MB). Its pinned SHA-256 is `422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898`.
