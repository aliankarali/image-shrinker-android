# Shrinker Pro for Android (unofficial port)

> **Unofficial.** This is an independent Android port of
> [Shrinker Pro](https://github.com/jeso87/ShrinkerPro) for macOS by Joshua
> Omilian, made under that project's MIT license. It is not affiliated with,
> endorsed by, or supported by the original author. Please report issues
> here, not to the macOS project.

An Android port of [Shrinker Pro](https://github.com/jeso87/ShrinkerPro) for macOS: minify PNG, JPEG,
HEIC, WebP, AVIF, GIF and SVG files on the device, with nothing uploaded
anywhere. Kotlin, Jetpack Compose and Material 3, minSdk 29 (Android 10).

The behaviour follows the desktop app closely. The UI follows Android's own
conventions rather than copying the Mac window.

## What it does

- **Add images** with *Choose files* or *Choose folder* (subfolders included,
  hidden entries skipped), by sharing from any app, through *Open with*, or by
  dragging onto the window on tablets, ChromeOS and desktop mode.
- **Compress every format in place**, or convert by format, using the same
  per-format rules as the desktop app (HEIC always converts, to JPEG by
  default; SVG and GIF never convert).
- **Quality**: Super Low / Low / Standard / High, with the same encoder values
  (Standard = JPEG 75, WebP and AVIF 80).
- **Never grows a file.** A same-format result that comes out no smaller is
  thrown away and the original kept, shown in the list as "original kept".
  Conversions and resizes are exempt, and growth is reported honestly (`+6%`).
- **Rotation is applied to the pixels**, never left as a tag.
- **Metadata policy**: all, copyright and credit only, or none.
- **Session bar** pinned to the bottom of the screen: *Convert all to*,
  *Quality*, *Max size* and *Crop to* (ratio or exact px). These apply to this
  session only and are never saved, and the summary is always on screen. A
  half-filled crop blocks both closing the panel and shrinking. A px crop
  overrides Max size. Nothing is ever enlarged.
- **Overwrite protection**: before replacing anything, the app asks once about
  originals and once about other existing files, with *Skip*, *Keep Both*
  (`photo.min 2.jpg`) or *Replace*.
- Results list with savings bars and before → after sizes. Tap to open, share
  button, *Clear*, optional auto-clear, and an optional notification when a
  batch finishes.

### Where results go

Android doesn't let an app write next to an arbitrary file, so the *Save to*
setting has three options:

| Option | Behaviour |
|---|---|
| **Pictures/Shrinker Pro** (default) | Through MediaStore; needs no permission. |
| **Same folder as original** | For files opened with *Choose folder*, results go beside each original (or into `minified/`). A file chosen on its own can only be written over in place ("Replace originals", same format). Anything else falls back to Pictures/Shrinker Pro. |
| **Choose folder…** | Any folder you grant through the system picker. |

"Keep originals, save a .min copy" vs "Replace originals" (or "Add .min" vs
"Leave as is" when writing elsewhere) works as on the Mac.

## How each format is handled

| Format | macOS app | This app |
|---|---|---|
| JPEG | mozjpeg 4.1.5 `cjpeg` | **mozjpeg 4.1.5**, set up identically — byte-for-byte the same output for the same pixels (Ultra HDR and 10/16-bit images use the platform encoder, which keeps the gain map) |
| PNG | pngquant | Built-in quantiser: exact palette when ≤ 256 colours, otherwise median cut + k-means + Floyd–Steinberg, written as an indexed PNG at the smallest bit depth |
| GIF | gifsicle `-O2` | Built-in GIF codec: lossless repack, frame-diff and full-frame encodings, smallest wins. Resize/crop keeps every frame. |
| SVG | svgo 4.1.0 in JavaScriptCore | **The same svgo 4.1.0 bundle**, in embedded QuickJS |
| WebP | `cwebp` | Platform encoder |
| AVIF | ImageIO | libavif (via `avif-coder`), with EXIF |
| HEIC | ImageIO (decode) | Platform decoder; libheif fallback |

Measured on the desktop app's test fixtures: JPEG 46 KB → 22 KB (−53%), PNG
244 KB → 61 KB (−75%), GIF 122 KB → 1 KB (−99%), SVG 163 → 109 bytes (identical
output to the Mac app).

## Differences from the macOS app

- **No command-line tool and no Sparkle updater.** Neither applies on
  Android; updates belong to the store.
- **Transparent images converted to JPEG are flattened onto white.** The Mac
  app doesn't flatten: JPEG drops the alpha channel, so transparent areas
  show whatever colour is stored behind them (black when the image was also
  rotated or resized).
- **XMP and maker notes aren't carried over.** EXIF text, camera, exposure,
  date and GPS tags are, byte-exact (so `©` survives). XMP isn't, because
  ExifInterface can only write tags as ASCII strings, which would corrupt XMP
  and maker notes.
- **English only for now.** Every string is in `res/values/strings.xml`, with
  plurals, ready for translation.

## Building

Open the project in Android Studio, or build from the command line. You need
JDK 17+ and the Android SDK with platform `android-37.2`, build-tools 36, NDK
29.0.14206865 and CMake 3.31.6 (the newest AndroidX releases need compileSdk
37). Gradle installs missing SDK components itself once the SDK licences are
accepted. Point it at your SDK with `ANDROID_HOME` or a `local.properties` file
containing `sdk.dir=…`; that file is machine-specific and git-ignored.

mozjpeg builds from the vendored tarball in `third_party/mozjpeg/` (hash-checked)
as part of the normal Gradle build. Its arm SIMD paths are used; the x86 builds
(emulators, ChromeOS) use its plain C paths, because the nasm assembler isn't
part of the NDK. They produce identical output, just more slowly.

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # minified; signed with the debug key — set up a real signingConfig before publishing
./gradlew bundleRelease        # App Bundle: per-ABI downloads keep install size down
./gradlew testDebugUnitTest    # JVM tests (61)
./gradlew connectedDebugAndroidTest   # device/emulator tests (27)
```

The release APK is about 32 MB, almost all of it native AVIF, QuickJS and
mozjpeg code for four ABIs (mozjpeg adds 250–440 KB per ABI). Every native
library is 16 KB page-aligned, as Google Play requires. Through an App Bundle
each device downloads only its own ABI.

mozjpeg compresses harder than the platform encoder and is slower: about
0.3 s against 0.03 s for a 12-megapixel photo on an arm64 emulator.

`scripts/prepare-svgo.sh` regenerates `app/src/main/assets/svgo.js` from npm.

## Tests

- `app/src/test` (JVM): crop geometry, resize planning, routing, session
  fields, naming, overwrite planning (including keep-both collisions within a
  batch), the PNG quantiser and writer (checked against the JDK's own PNG
  decoder), the GIF codec (LZW round trips, pixel-exact optimisation checked
  against the JDK's decoder, disposal modes, resized animations), the
  resampler, and EXIF text restoration. Many cases are ported from the macOS
  test suite and use its fixtures.
- `app/src/androidTest` (device): mozjpeg output byte-identical to the desktop
  app's `cjpeg` at every quality level (golden files in
  `androidTest/assets/mozjpeg`), its subsampling rules, encoder routing (Ultra
  HDR keeps its gain map), Display P3 profiles; every format and conversion end to end,
  never-grow, crops on every route, all eight EXIF orientations through each
  decode path, metadata policies on JPEG/WebP/AVIF, in-place replacement, and
  MediaStore writes.

## License

MIT; see [LICENSE](LICENSE). This is a port of
[Shrinker Pro](https://github.com/jeso87/ShrinkerPro) by Joshua Omilian (MIT),
whose test fixtures it reuses. It bundles
[mozjpeg](https://github.com/mozilla/mozjpeg) (IJG/BSD/zlib),
[svgo](https://github.com/svg/svgo) (MIT),
[avif-coder](https://github.com/awxkee/avif-coder) (Apache 2.0),
[quickjs-wrapper](https://github.com/HarlonWang/quickjs-wrapper) (Apache 2.0)
and Material icons (Apache 2.0); see
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
