# Reel Skipper

Reel Skipper is a small Android app that uses an accessibility service to skip sponsored Instagram content while you scroll.

I made it for personal use because Reels ads break the flow, especially when moving quickly between videos. The app watches the visible Instagram screen, looks for ad labels or common ad buttons, then performs the same direction of action you were already using. If you were moving down, it skips down. If you were moving back up, it skips up.

## what it handles

- Instagram Reels
- Instagram Stories
- The main feed when an ad marker is visible
- Forward and backward scrolling
- Custom ad keywords from the app screen

## what it does not do

This is not a modified Instagram APK. It does not patch Instagram, hook its process, root the phone, or block network requests.

It only uses Android Accessibility APIs. That means it reacts after Instagram draws something on screen, so the result depends on what Instagram exposes through accessibility on your phone.

## build

This project uses Gradle and Kotlin.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleDebug
```

The debug APK will be created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## setup on phone

Install the APK, open the app, then enable "Reel Skipper" in Android Accessibility settings.

After that, open Instagram and scroll normally. The app only runs its skip logic when Instagram is the foreground app.

## privacy

The app has no internet permission and does not send data anywhere. It stores only local settings, such as whether skipping is enabled and which keywords should count as ad markers.

## notes

Instagram changes its UI often. If skipping stops working, the first thing to check is whether Instagram changed the text labels or view ids exposed to accessibility.
