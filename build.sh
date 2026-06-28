#!/bin/bash

VERSION=$(cat version.md | tr -d '[:space:]')
OUTPUT_DIR="."
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"

show_help() {
    echo "AROMA Build Script v${VERSION}"
    echo ""
    echo "Usage: ./build.sh [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --help          Show this help message"
    echo "  --release       Build release APK, sign it, and create GitHub release"
    echo "  --emulator      Build debug APK and install into running emulator/device"
    echo "  (no args)       Build debug APK and copy to ~/Downloads"
    echo ""
    echo "Examples:"
    echo "  ./build.sh                  Build debug APK"
    echo "  ./build.sh --emulator       Build and install on emulator"
    echo "  ./build.sh --release        Build and release signed APK"
    echo "  ./build.sh --help           Show this help"
    echo ""
    echo "Output locations:"
    echo "  Debug APK:   ~/Downloads/aroma-debug-${VERSION}.apk"
    echo "  Release APK: ${OUTPUT_DIR}/aroma-release-${VERSION}-signed.apk"
}

install_to_device() {
    local APK_PATH="$1"
    local LABEL="$2"

    echo ""
    echo "Checking for connected emulator/device..."

    if ! "$ADB" version &>/dev/null; then
        echo "adb not found at: $ADB"
        echo "Set ANDROID_HOME or install Android SDK platform-tools."
        exit 1
    fi

    # Wait briefly for a device to appear
    "$ADB" wait-for-device &
    ADB_WAIT_PID=$!
    sleep 3
    kill $ADB_WAIT_PID 2>/dev/null

    DEVICES=$("$ADB" devices | grep -E "emulator|device$" | grep -v "List of")
    if [ -z "$DEVICES" ]; then
        echo "No emulator or device found. Start an emulator first:"
        echo "  \$ANDROID_HOME/emulator/emulator -avd <AVD_NAME> &"
        exit 1
    fi

    echo "Found device(s):"
    echo "$DEVICES"
    echo ""

    # Pick the first available target (emulator preferred)
    TARGET=$("$ADB" devices | grep "emulator" | head -1 | awk '{print $1}')
    if [ -z "$TARGET" ]; then
        TARGET=$("$ADB" devices | grep "device$" | head -1 | awk '{print $1}')
    fi

    echo "Installing ${LABEL} on: ${TARGET}"
    "$ADB" -s "$TARGET" install -r "$APK_PATH"

    if [ $? -eq 0 ]; then
        echo ""
        echo "Installed successfully on ${TARGET}."
        echo "Launching AROMA..."
        "$ADB" -s "$TARGET" shell am start -n com.example.aroma/.MainActivity
    else
        echo "Installation failed."
        exit 1
    fi
}

if [ "$1" == "--help" ] || [ "$1" == "-h" ]; then
    show_help
    exit 0
fi

if [ "$1" == "--release" ]; then
    echo "Building release APK..."
    ./gradlew assembleRelease
    
    if [ $? -ne 0 ]; then
        echo "Build failed!"
        exit 1
    fi
    
    APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"
    UNSIGNED_APK="${OUTPUT_DIR}/aroma-release-${VERSION}-unsigned.apk"
    SIGNED_APK="${OUTPUT_DIR}/aroma-release-${VERSION}-signed.apk"
    
    cp "$APK_PATH" "$UNSIGNED_APK"
    echo "Unsigned APK copied to $UNSIGNED_APK"
    
    # Sign the APK
    echo ""
    echo "Signing APK..."
    APKSIGNER="/Users/aicoder/Library/Android/sdk/build-tools/35.0.0/apksigner"
    "$APKSIGNER" sign --ks ~/.android/debug.keystore --ks-pass pass:android --out "$SIGNED_APK" "$UNSIGNED_APK" 2>&1
    
    if [ $? -eq 0 ]; then
        echo "Signed APK created: $SIGNED_APK"
    else
        echo "APK signing failed!"
        exit 1
    fi
    
    echo ""
    echo "Creating GitHub release v${VERSION}..."
    
    # Check if gh is installed
    if ! command -v gh &> /dev/null; then
        echo "GitHub CLI (gh) not found. Install it with: brew install gh"
        echo "Then run: gh release create v${VERSION} $SIGNED_APK --title \"AROMA v${VERSION}\" --notes \"Release v${VERSION}\""
        exit 1
    fi
    
    # Delete existing release if it exists
    if gh release view "v${VERSION}" &>/dev/null; then
        echo "Release v${VERSION} already exists, replacing..."
        gh release delete "v${VERSION}" --yes
        git tag -d "v${VERSION}" 2>/dev/null
        git push origin --delete "v${VERSION}" 2>/dev/null
    fi

    # Create GitHub release with signed APK
    gh release create "v${VERSION}" "$SIGNED_APK" \
        --title "AROMA v${VERSION}" \
        --generate-notes
    
    if [ $? -eq 0 ]; then
        echo "GitHub release v${VERSION} created successfully!"
    else
        echo "Failed to create GitHub release. You may need to run: gh auth login"
    fi

elif [ "$1" == "--emulator" ]; then
    echo "Building debug APK for emulator install..."
    ./gradlew assembleDebug

    if [ $? -ne 0 ]; then
        echo "Build failed!"
        exit 1
    fi

    DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
    install_to_device "$DEBUG_APK" "aroma-debug-${VERSION}.apk"

else
    echo "Building debug APK..."
    ./gradlew assembleDebug
    cp app/build/outputs/apk/debug/app-debug.apk ~/Downloads/aroma-debug-${VERSION}.apk
    echo "Debug APK copied to ~/Downloads/aroma-debug-${VERSION}.apk"
fi
