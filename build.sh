#!/bin/bash

VERSION="0.0.6"
OUTPUT_DIR="/Users/aicoder/src/grapheneos-shizuku/apks"

show_help() {
    echo "AROMA Build Script v${VERSION}"
    echo ""
    echo "Usage: ./build.sh [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --help      Show this help message"
    echo "  --release   Build release APK, sign it, and create GitHub release"
    echo "  (no args)   Build debug APK and copy to ~/Downloads"
    echo ""
    echo "Examples:"
    echo "  ./build.sh              Build debug APK"
    echo "  ./build.sh --release    Build and release signed APK"
    echo "  ./build.sh --help       Show this help"
    echo ""
    echo "Output locations:"
    echo "  Debug APK:   ~/Downloads/aroma-debug-${VERSION}.apk"
    echo "  Release APK: ${OUTPUT_DIR}/aroma-release-${VERSION}-signed.apk"
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
    
    # Copy unsigned APK to output directory
    mkdir -p "$OUTPUT_DIR"
    cp "$APK_PATH" "$UNSIGNED_APK"
    echo "Unsigned APK copied to $UNSIGNED_APK"
    
    # Sign the APK
    echo ""
    echo "Signing APK..."
    apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --out "$SIGNED_APK" "$UNSIGNED_APK" 2>&1
    
    if [ $? -eq 0 ]; then
        echo "Signed APK created: $SIGNED_APK"
    else
        echo "APK signing failed!"
        exit 1
    fi
    
    # Also copy to Downloads for convenience
    cp "$SIGNED_APK" ~/Downloads/
    echo "Signed APK also copied to ~/Downloads/"
    
    echo ""
    echo "Creating GitHub release v${VERSION}..."
    
    # Check if gh is installed
    if ! command -v gh &> /dev/null; then
        echo "GitHub CLI (gh) not found. Install it with: brew install gh"
        echo "Then run: gh release create v${VERSION} $SIGNED_APK --title \"AROMA v${VERSION}\" --notes \"Release v${VERSION}\""
        exit 1
    fi
    
    # Create GitHub release with signed APK
    gh release create "v${VERSION}" "$SIGNED_APK" \
        --title "AROMA v${VERSION}" \
        --notes "## AROMA v${VERSION}

### New in this release
- Upload progress bar with percentage, speed, and byte tracking
- Remote terminal shell (/terminal) for executing commands on device
- File info panel showing size and details when selecting files
- Fixed Firefox POST resubmit warning (PRG pattern)

### Features
- File manager web interface with dark/light theme support
- Upload, download, rename, delete files
- Create folders
- Alphabetical file sorting
- ngrok tunnel support for remote access
- QR code for easy connection
- Configurable storage folder (Downloads, Documents, Pictures, Music, Movies)
- HTTP Basic Auth for security

### Installation
Download the APK and install on your Android device."
    
    if [ $? -eq 0 ]; then
        echo "GitHub release v${VERSION} created successfully!"
    else
        echo "Failed to create GitHub release. You may need to run: gh auth login"
    fi
else
    echo "Building debug APK..."
    ./gradlew build --info
    ./gradlew assemble
    cp app/build/outputs/apk/debug/app-debug.apk ~/Downloads/aroma-debug-${VERSION}.apk
    echo "Debug APK copied to ~/Downloads/aroma-debug-${VERSION}.apk"
fi
