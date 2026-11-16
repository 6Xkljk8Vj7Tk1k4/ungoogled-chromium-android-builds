# ungoogled-chromium-android-builds

Podman-based builder for Chromium on Android (arm64-v8a) with the
[ungoogled-chromium](https://github.com/ungoogled-software/ungoogled-chromium)
patch set, plus ready-made APKs in [Releases](../../releases).

Unofficial. Not affiliated with the Chromium or ungoogled-chromium projects.

## Install

Download the APK from the latest release and open it. Requires Android 10 or
newer on a 64-bit ARM device.

The browser updates itself: once a day it asks the GitHub API for the latest
release of this repository, downloads a newer APK on an unmetered network,
checks its size and SHA-256 and hands it to the system installer. Android asks
once for permission to install apps from this source. Nothing else is sent
anywhere.

Every APK is signed with the same key. Its certificate SHA-256 is listed in
each release; Android refuses an update signed with any other key.

## What is different from Chromium

- The full ungoogled-chromium patch series and its domain substitution
  (Google host names in the source are replaced with non-existent ones).
- Google services switched off with GN flags: API keys, reporting, sign-in
  and others. The list, with reasons, is in `Containerfile.android`.
- Privacy defaults compiled in, so they hold from the first start: Safe
  Browsing off, no search suggestions, no network prediction, Do Not Track on,
  password manager and autofill off.
- No default search engine. Pick one in Settings.

The ungoogled-chromium project does not maintain an Android port; this
repository applies its desktop patches to the Android tree and fixes what
breaks.

## Build it yourself

You need Podman, about 150 GB of free disk space and several hours.

```bash
./build_android.sh                 # latest ungoogled-chromium release
./build_android.sh --version 157.0.8103.78-1
./build_android.sh --update-repo ''   # without the self-updater
```

Chromium itself is not in this repository: the build fetches the exact tag
from chromium.googlesource.com and the matching ungoogled-chromium release,
then applies the changes kept here.

The self-updater needs your own signing key (`./keystore_android.sh
generate`) and your own releases repository (`android_update.conf`).
`./release_android.sh` checks an APK and publishes it.

## License

BSD-3-Clause, the same as Chromium and ungoogled-chromium. Licenses of all
bundled components: open `chrome://credits` in the browser.

Comments in the build files are in Polish.
