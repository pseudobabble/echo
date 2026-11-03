# Echo

Offline-first Android voice notes with local audio retention and queued on-device transcription.

## v1 architecture

Audio is recorded to app-private storage, indexed in Room, transcribed by a foreground `WorkManager` job, and copied into a user-selected Google Drive folder by independently retryable jobs. The recording notification supplies the lock-screen entry point.

## whisper.cpp integration

The app deliberately has no cloud-transcription fallback. `WhisperEngine` is the native integration boundary; before shipping, vendor a pinned `whisper.cpp` release in `app/src/main/cpp/whisper.cpp`, add its JNI binding from the official Android example, and install a selected model pack. Models must be downloaded after install (Wi-Fi by default) and checksum verified; do not package them in the APK.

## Google Drive v1

Tap **Drive**, select a folder supplied by the Google Drive app, and grant write access. Android persists this folder grant; the worker writes the `.m4a` audio and matching `.txt` transcript after local transcription. This uses the phone's existing Google Drive account and needs no embedded credentials, OAuth client, or Google Cloud configuration. S3 is deliberately deferred.

## Model decision

V1 uses the multilingual `ggml-base-q5_1.bin` whisper.cpp model (~57 MB). It must be installed as a post-install model pack and verified before use.
