#!/usr/bin/env bash
# Idempotent Cloud Agent setup for the CARFU / Dicio Android app.
# Installs the Android SDK (if missing) and warms the Gradle build so that
# `./gradlew assembleDebug testDebugUnitTest` works end to end.
set -euo pipefail

# --- Toolchain locations -----------------------------------------------------
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

CMDLINE_TOOLS_VERSION="13114758" # cmdline-tools 19.0
PLATFORM_VERSION="36"
BUILD_TOOLS_VERSION="36.0.0"

echo "==> JAVA_HOME=$JAVA_HOME"
java -version

# --- JDK 17 (Gradle toolchain for the git-based dicio-* subprojects) ---------
# The build runs on JDK 21, but the included dicio-numbers / dicio-sentences-
# compiler builds request a Java 17 toolchain. Gradle auto-detects JDKs under
# /usr/lib/jvm, so make sure a JDK 17 is present there.
if [ ! -d /usr/lib/jvm/java-17-openjdk-amd64 ]; then
    echo "==> Installing OpenJDK 17 (Gradle toolchain dependency)"
    sudo apt-get update -qq
    sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-17-jdk-headless
else
    echo "==> OpenJDK 17 already present"
fi

# --- Android command line tools ---------------------------------------------
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
    echo "==> Installing Android command line tools into $ANDROID_HOME"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    tmp_zip="$(mktemp --suffix=.zip)"
    curl -fsSL \
        "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
        -o "$tmp_zip"
    rm -rf "$ANDROID_HOME/cmdline-tools/latest" "$ANDROID_HOME/cmdline-tools/cmdline-tools"
    unzip -q "$tmp_zip" -d "$ANDROID_HOME/cmdline-tools"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -f "$tmp_zip"
else
    echo "==> Android command line tools already present"
fi

# --- Accept licenses and install SDK packages --------------------------------
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
echo "==> Installing SDK packages"
"$SDKMANAGER" \
    "platform-tools" \
    "platforms;android-${PLATFORM_VERSION}" \
    "build-tools;${BUILD_TOOLS_VERSION}" >/dev/null

# --- Point Gradle at the SDK -------------------------------------------------
cat > "$(dirname "$0")/../local.properties" <<EOF
sdk.dir=$ANDROID_HOME
EOF

# --- Warm the Gradle build (fetches git-based dependencies) ------------------
cd "$(dirname "$0")/.."
echo "==> Warming Gradle dependencies"
./gradlew --no-daemon help >/dev/null

echo "==> Android SDK and Gradle setup complete"
