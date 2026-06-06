<div align="center">

<img src="https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white"/>
<img src="https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white"/>
<img src="https://img.shields.io/badge/Camera2_API-FF6F00?style=for-the-badge&logo=google&logoColor=white"/>
<img src="https://img.shields.io/badge/Jetpack_Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white"/>

# 📷 MultiCam

**Simultaneous dual-camera recording for Android.**  
Two lenses. One tap. Zero compromises.

</div>

---

## What it does

MultiCam records from **two rear cameras at the same time** — ultra-wide (0.5×) and main (1×) — each to its own MP4 file, both with audio, with independently configurable quality and framerate.

No stitching. No switching. Two separate video files, ready to edit.

---

## How it works

Android doesn't make this easy. MultiCam tries two strategies in order:

```
┌─────────────────────────────────────────────────────┐
│  1. CameraX Concurrent Camera API                   │
│     → cleanest path, works if the device exposes    │
│       a concurrent-capable rear pair                │
└────────────────────┬────────────────────────────────┘
                     │ fails (most devices, incl. Pixel 7a)
                     ▼
┌─────────────────────────────────────────────────────┐
│  2. Camera2 Physical Camera fallback                │
│     → opens the logical rear camera, binds two      │
│       physical sensor IDs via OutputConfiguration   │
│       .setPhysicalCameraId()                        │
│     → recording session uses ONLY recorder surfaces │
│       (no preview streams) for maximum encoder      │
│       headroom                                      │
└─────────────────────────────────────────────────────┘
```

Audio is captured **once** (from the 0.5× stream) and muxed into both output files via `MediaExtractor` + `MediaMuxer` after recording stops — no double microphone opening, no sync issues.

---

## Features

| | |
|---|---|
| 🎬 | Simultaneous dual-camera recording |
| 🎙️ | Audio on both output files |
| ⚙️ | Per-camera quality selector — Max / 4K / 1080p / 720p |
| 🎞️ | Per-camera FPS selector — 30 / 60 fps |
| 🚫 | Preview disabled during recording (frees encoder headroom) |
| 📁 | Auto-export to `Movies/MultiCam` via MediaStore |
| 🔁 | Automatic CameraX → Camera2 fallback |
| 📊 | Live camera diagnostics if hardware blocks dual streaming |
| 🌑 | Dark UI built with Jetpack Compose |

---

## Screenshots

> *Two preview tiles side by side. Tap record. Both cameras roll.*

```
┌──────────────────────────────────────────┐
│  MultiCam                      ● READY   │
│  Fallback Camera2 pronto                 │
├───────────────────┬──────────────────────┤
│  📷 0.5x          │  📷 1x               │
│  physical ID 2    │  physical ID 3       │
│                   │                      │
│   [live preview]  │   [live preview]     │
│                   │                      │
├───────────────────┴──────────────────────┤
│  0.5x  [Max] [4K] [1080p] [720p]  [30fps] [60fps] │
│   1x   [Max] [4K] [1080p] [720p]  [30fps] [60fps] │
├──────────────────────────────────────────┤
│  🎙 Audio on both files          [ ⏺ ]  │
└──────────────────────────────────────────┘
```

---

## Requirements

- Android **9.0+** (API 28) — required for `setPhysicalCameraId()`
- A device with a **logical multi-camera** rear group (two or more physical sensors behind one logical ID)
- `CAMERA` + `RECORD_AUDIO` permissions

> Tested on **Pixel 7a** — CameraX concurrent not available, Camera2 physical fallback active.

---

## Output files

After each recording session, two files are saved to:

```
/sdcard/Movies/MultiCam/
├── MultiCam_20250606_143021_0_5x.mp4   ← ultra-wide + audio
└── MultiCam_20250606_143021_1x.mp4     ← main + audio (muxed)
```

Files are immediately visible in Google Photos / Files app.

---

## Build

```bash
git clone https://github.com/RuggeroCadamuroITA/MultiCam
cd MultiCam
./gradlew assembleDebug
```

Install:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Stack

- **Jetpack Compose** — UI
- **CameraX** — concurrent camera (primary path)
- **Camera2 API** — physical camera fallback, recorder sessions
- **MediaRecorder** — H264 encoding per physical sensor
- **MediaMuxer / MediaExtractor** — post-recording audio mux
- **MediaStore** — public export to Movies/

---

## Known limitations

- Preview is frozen during recording by design — encoder headroom
- If the device doesn't expose physical camera IDs on the rear logical camera, dual recording is not possible via public APIs
- 4K simultaneous on both streams may cause frame drops depending on device thermals and encoder availability

---

## License

MIT

---

<div align="center">
  <sub>Built for Pixel 7a · Camera2 physical camera API · Jetpack Compose</sub>
</div>
