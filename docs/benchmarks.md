# Benchmarks: Parallax vs the Google TV launcher

Measured on 30 October 2026 on a TCL C61K 55" (Google TV, Android 14, MediaTek `merak`, 4 cores, 2.4 GB RAM,
32-bit armeabi-v7a, UI at 1920×1080). Parallax 0.1.0 is a release build (commit `f697ab6`), compiled with
`cmd package compile -m speed -f`. The stock launcher is `com.google.android.apps.tv.launcherx` 1.0.994451607.
Both launchers ran on the same TV in one sitting, over adb on Ethernet.

## Results

| Metric | Parallax | Google TV | |
| --- | ---: | ---: | --- |
| Cold start, median of 7 (`am start -S -W`) | **444 ms** | 490 ms | 9% faster |
| Warm start, median of 7 (back from Settings) | **68 ms** | 104 ms | 35% faster |
| Memory after 15 s idle (total PSS) | **63.5 MB** | 89.0 MB | 29% less |
| — Java heap | 5.6 MB | 22.2 MB | |
| — Native heap | 41.4 MB | 19.3 MB | likely Top Shelf and gradient bitmaps |
| APK size | **0.96 MB** | 24.8 MB | 26× smaller |
| Installed size (app + data) | **8.8 MB** | 220 MB | |
| CPU while idle on the resting screen (`top`, 5 × 2 s) | 35% of one core | **0%** | see below |
| D-pad scroll, frames rendered (60 presses) | 972 | 209 | |
| — Janky frames (frame deadline missed) | **1.1%** | 2.9% | |
| — Frame time p50 / p90 / p99 | 31 / 36 / 57 ms | **19 / 27** / 89 ms | |

Cold-start runs (ms): Parallax 848, 618, 444, 468, 427, 417, 435. Google TV 624, 482, 489, 490, 460, 490, 569.
The first Parallax run was slower because the process had to load from storage after its first start.

## Notes

- **Idle CPU is a regression from the animated gradient** (`AnimatedGradient.kt`). While the resting screen is
  showing, Parallax renders a new gradient frame on every vsync, which keeps about 35% of one core busy and
  explains why it drew 4.6× as many frames as the stock launcher in the scroll test. The gradient stops once the
  app row is shown, but the frame clock never rests on the home screen. Rendering the gradient at a few frames
  per second, or pausing it after some idle time, should bring this close to zero.
- **Frame times.** Parallax misses fewer frame deadlines, but its median frame takes longer to draw (31 ms
  vs 19 ms). Its frames include the full-screen gradient and the glass widgets; the stock launcher draws mostly
  static layouts. Neither launcher holds 60 fps on this chip; Parallax's p99 stays lower (57 ms vs 89 ms).
- Warm start reuses the existing task (`Activity not started, its current task has been brought to the front`)
  for both launchers, which is what pressing Home does.
- The Google TV launcher was re-enabled only for the test, launched directly by component, and disabled again
  afterwards.

## Reproducing

```bash
adb shell pm enable com.google.android.apps.tv.launcherx   # only for the test
bash docs/bench.sh com.parallax.launcher com.parallax.launcher/.MainActivity Parallax
bash docs/bench.sh com.google.android.apps.tv.launcherx com.google.android.apps.tv.launcherx/.home.HomeActivity "Google TV"
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
```

Set `S` to your TV's adb address first, e.g. `export S=192.168.1.20:5555`.
