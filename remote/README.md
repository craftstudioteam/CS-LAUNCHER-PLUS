# CS Launcher V3 — Remote Loading Video

This folder is the launcher's **remote media store**. Everything here is
served over GitHub Raw at launcher start — nothing inside `remote/` is
compiled into the APK, so the APK size never changes when these files change.

## Files

| File | Purpose |
|---|---|
| `config.json` | Remote switchboard. Fetched on every launcher start (and refreshed every 30 min in the background). |
| `loading.mp4` | The loading-screen video. Current: **5.55 MB, H.264 (AVC) + AAC, MP4, faststart (moov first)** — instantly streamable + fully Android-compatible. |

## config.json format

```json
{
  "loadingVideo": {
    "enabled": true,
    "url": "https://raw.githubusercontent.com/PAPA20000/CSL/main/remote/loading.mp4"
  }
}
```

- `enabled: false` → launcher always shows the **Classic Black** loading screen.
- `enabled: true` + user picked **Video Loading Screen** in
  *Settings → Launcher Settings → Loading Screen* → the remote video plays
  until the game window renders its first frame (then the player is released
  immediately).

## Updating the video — NO APK release needed

1. Replace `remote/loading.mp4` (keep the same filename, or change `url` in
   `config.json` accordingly).
2. Commit + push to `main`. GitHub Raw reflects it within a few minutes.
3. On the next launcher start the config refresh probes the remote file
   (ETag / Last-Modified / size). If it changed, the new video is downloaded
   to a **temporary in-app cache** and plays from then on.

## Video spec (keep it compatible)

- Container: **MP4** (`ftyp` isom/mp42 — not MKV/WebM)
- Video codec: **H.264 (AVC)**, baseline/main/high profile
- Audio: **AAC** (playback is muted by design)
- Recommended: 720p or 1080p, 30 FPS, short loop (< ~15 s), ≤ ~10 MB
- **faststart required**: moov atom must sit BEFORE mdat (e.g. `ffmpeg -movflags +faststart` or qtfaststart) — otherwise progressive streaming stalls on many devices

## Fail-safes (launcher side)

no internet / config download fails / video stream or download fails /
decode error / 7 s stall watchdog → the launcher automatically falls back to
the Classic Black loading stage. **The launcher never crashes because of
this folder.**

## Cache rules

- Exactly one throwaway copy lives in the app cache dir, keyed by URL.
- Same URL + unchanged remote file → instant local playback, zero re-download.
- Changed URL, or the file replaced at the same URL → re-fetched
  automatically on the next launcher start.
- The OS may evict the cache at any time; the launcher then simply streams
  the live URL again.
