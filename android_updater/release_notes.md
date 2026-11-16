Chromium {{VERSION}} for Android (arm64-v8a) with the ungoogled-chromium patch set. Unofficial build, not affiliated with the Chromium or ungoogled-chromium projects.

**Install:** download the APK below and open it. Requires Android 10 or newer on a 64-bit ARM device.

**Updates:** the browser checks this repository's releases once a day and installs newer versions by itself. Android asks once for permission to install apps from this source.

**Good to know**
- No Google services: API keys, sign-in, sync, Safe Browsing and search suggestions are disabled or removed at build time.
- One switch is left as upstream ships it: Settings → Google services → "Help improve Chrome's features and performance". Turn it off by hand.
- No default search engine is set. Pick one in Settings → Search engine.
- The APK is signed with this project's own key. It cannot be installed over a Chromium build with the same package name signed by someone else; uninstall that one first.

**Verify the download**
- APK SHA-256: `{{SHA256}}`
- Signing certificate SHA-256: `{{CERT}}`
