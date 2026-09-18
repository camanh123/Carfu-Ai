# Phase 3A.1 — installability only

`android:testOnly="true"` was **not** in `src/main/AndroidManifest.xml`.
It was injected by Android Gradle Plugin into the **debug** merged manifest
(`android.injected.testOnly`, default for debug packaging).

That flag is a real ordinary-sideload blocker: Package Installer does not
pass `INSTALL_ALLOW_TEST`.

This module sets `android.injected.testOnly=false` and enables v1+v2 signing
on the existing Android Debug identity. ASR / JNI / model / UI are unchanged.

The CARFU unit did not capture `INSTALL_FAILED_*`, so the device failure is
not claimed as proven `INSTALL_FAILED_TEST_ONLY`.
