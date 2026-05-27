# Screen Reader

Android accessibility tool for one-tap screen OCR and Chinese text-to-speech, aimed at elderly and low-vision users reading text embedded in images such as WeChat screenshots.

## Current MVP scaffold

This repository now contains a minimal Android app skeleton with:

- a setup/status screen
- a foreground floating overlay service
- an accessibility service hook
- Google ML Kit Chinese OCR integration
- Android text-to-speech integration
- explicit stop controls through both the app and persistent notification

## Current limitations

- This first pass only uses `AccessibilityService.takeScreenshot()`, so full screen OCR capture currently requires Android 11+.
- The `MediaProjection` fallback for Android 8-10 is not implemented yet.
- Xiaomi/MIUI-specific autostart and battery handling screens are not implemented yet.
- Gradle wrapper files are not included yet, and this shell does not currently have Java or Gradle installed for local compilation.

## Intended first test flow

1. Grant overlay permission.
2. Enable the accessibility service.
3. Start the floating button.
4. Open an image containing Chinese text in WeChat or another app.
5. Tap the floating button.
6. The app captures the screen, runs OCR, and reads recognized text aloud.

## Safety notes

- The overlay can be stopped from the main app screen.
- Speech can be stopped from the main app screen or notification action.
- The overlay service stays visible through a persistent notification so it is recoverable during testing.
