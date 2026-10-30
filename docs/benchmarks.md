# Benchmarks: Parallax vs the Google TV launcher

Measured on 30 October 2026 on a TCL C61K 55" (Google TV, Android 14, MediaTek `merak`, 4 cores, 2.4 GB RAM,
32-bit armeabi-v7a, UI at 1920×1080). Parallax 0.1.0 is a release build, compiled with
`cmd package compile -m speed -f`. The stock launcher is `com.google.android.apps.tv.launcherx` 1.0.994451607.
Both launchers ran on the same TV, over adb on Ethernet.

## Results

| Metric | Parallax | Google TV | |
| --- | ---: | ---: | --- |
| Cold start, median of 7 (`am start -S -W`) | **444 ms** | 490 ms | 9% faster |
| Warm start, median of 7 (back from Settings) | **65–68 ms** | 104 ms | about 35% faster |
| Memory after 15 s idle (total PSS) | 50–92 MB | 89 MB | see below |
| APK size | **0.96 MB** | 24.8 MB | 26× smaller |
| Installed size (app + data) | **8.8 MB** | 220 MB | |
| CPU while idle on the resting screen (`top`, 5 × 2 s) | 10.5% of one core | **0%** | was 35% before the fix |
| D-pad scroll, frames rendered (60 presses) | 865–972 | 209 | |
| — Janky frames (frame deadline missed) | **1.1–2.0%** | 2.9% | |
| — Frame time p50 / p90 / p99 | 31–34 / 36–42 / 57 ms | **19 / 27** / 89 ms | |

Where Parallax shows a range, it was measured in two sessions (before and after the gradient fix below), and
the range covers both.

Cold-start runs (ms): Parallax 848, 618, 444, 468, 427, 417, 435; Google TV 624, 482, 489, 490, 460, 490, 569.
The first Parallax run was slower because the process had to load from storage after its first start. As the
default home, Parallax is relaunched by the system right after a force-stop, so in later sessions
`am start -S` only found it already running. Measured instead from the system's `Displayed` log after a
force-stop and a Home press, Parallax cold-starts in 460–498 ms (median 473 ms).

## Notes

- **Idle CPU.** The resting screen's drifting gradient (`AnimatedGradient.kt`) first cost about 35% of one core
  at 20 updates a second: the render recomputed `sin`/`cos` for every blob at every pixel and allocated an
  iterator per pixel, which also kept the garbage collector busy. Blob positions are now computed once per
  update and the pixel loop allocates nothing, and the gradient updates 10 times a second (each step shifts a
  colour by about one level in 255, which isn't visible). Rendering the gradient itself now costs about 2% of a
  core. The remaining ~10% is uploading the bitmap and redrawing the screen and glass widgets 10 times a
  second. The stock launcher's resting screen doesn't animate, so it sits at 0%.
- **Memory** depends on how many Top Shelf previews are cached. In four samples after a fresh start Parallax
  used 50, 50, 50 and 70 MB. At the end of the two full benchmark runs, after many starts, it used 64 and 92 MB. The Google TV figure is
  a single sample. Parallax's Java heap is small (5.6–6.5 MB vs 22 MB); most of its memory is native bitmaps.
- **Frame times.** The gradient is paused while the app row is shown, so it plays no part in the scroll test.
  Parallax draws far more frames there because of its springy focus and tilt animations. It misses fewer frame
  deadlines and has a lower p99 (57 ms vs 89 ms), but its median frame takes longer (31–34 ms vs 19 ms).
  Neither launcher holds 60 fps on this chip.
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
