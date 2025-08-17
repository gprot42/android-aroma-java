
```
cat ~/.android/avd/emulate-android15.ini
avd.ini.encoding=UTF-8
path=/Users/darrenevans/.android/avd/emulate-android15.avd
path.rel=avd/emulate-android15.avd
target=android-35
hw.keyboard = yes
hw.sdCard = yes

./gradlew clean
./gradlew dependencies
./gradlew assembleDebug --info

# For more serious errors
./gradlew assembleDebug --stacktrace

$ sdkmanager --verbose "system-images;android-35;google_apis;arm64-v8a"

$ avdmanager create avd -n emulate-android15 -k "system-images;android-35;google_apis;arm64-v8a" -d pixel_8_pro

# Different shell, start the emulator
$ emulator -avd emulate-android15 -no-snapshot-save -writable-system

$ adb forward tcp:8080 tcp:8080

$ adb -s emulator-5554 install app/build/outputs/apk/debug/app-debug.apk

# Click on start server within the app

$ adb shell am start -n "com.example.aroma/.MainActivity"

$ adb shell input text "your_ngrok_token_here"

# Clear all logs if necessary before starting your app
$ adb logcat -c
$ adb logcat | grep com.example.aroma
# Errors only
$ adb logcat *:E
$ adb shell ping google.com

# To interact with the phones emulated filesystem
$ adb shell
$ adb shell ls /sdcard/Download

# Point your web browser to
http://localhost:8080
```
