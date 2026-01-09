#!/bin/bash

VERSION="0.2"

if [ "$1" == "--release" ]; then
    echo "Building release APK..."
    ./gradlew assembleRelease
    
    if [ $? -ne 0 ]; then
        echo "Build failed!"
        exit 1
    fi
    
    APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"
    RELEASE_APK="aroma-release-${VERSION}.apk"
    
    cp "$APK_PATH" ~/Downloads/"$RELEASE_APK"
    echo "Release APK copied to ~/Downloads/$RELEASE_APK"
    
    echo ""
    echo "Creating GitHub release v${VERSION}..."
    
    # Check if gh is installed
    if ! command -v gh &> /dev/null; then
        echo "GitHub CLI (gh) not found. Install it with: brew install gh"
        echo "Then run: gh release create v${VERSION} ~/Downloads/$RELEASE_APK --title \"AROMA v${VERSION}\" --notes \"Release v${VERSION}\""
        exit 1
    fi
    
    # Create GitHub release
    gh release create "v${VERSION}" ~/Downloads/"$RELEASE_APK" \
        --title "AROMA v${VERSION}" \
        --notes "## AROMA v${VERSION}

### Features
- File manager web interface with dark theme
- Upload, download, rename, delete files
- Create folders
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
