# Google Play data safety

Working answers checked against the signed version `0.10.0-beta.2` / code `25` (`com.majkeylab.seliadocs`) on 2026-10-13. Its merged permission list is unchanged from code 17. The APK still uses Google ML Kit Digital Ink Recognition `19.0.0` and Text Recognition `16.0.1`. Recheck these answers whenever dependencies or data handling change.

## Collection and sharing

- Does the app collect data? **Yes.** Google ML Kit Digital Ink and Text Recognition collect SDK metadata and metrics.
- Does the app share user data? **No**
- Is all collected user data encrypted in transit? **Yes**
- Can users request data deletion? **No publisher-managed deletion request is offered for Google-held ML Kit SDK metrics.** Users can permanently delete local notebook data or uninstall the app.
- Independent security review badge: **No**

## Required collected data declarations

Declare these as collected, required, used for Analytics, and not shared:

- Device or other IDs
- App interactions
- Diagnostics

Google's [ML Kit disclosure](https://developers.google.com/ml-kit/android-data-disclosure) also covers device and app information, performance metrics, API configuration, input and output sizes, event types, and error codes. Digital Ink Recognition also collects the configured language. Do not declare Google-held SDK metrics as ephemeral or publisher-deletable.

## App behavior supporting these answers

- `INTERNET` and `ACCESS_NETWORK_STATE` permissions support Google ML Kit language-model downloads and SDK diagnostics.
- The merged release manifest also contains `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, and `FOREGROUND_SERVICE`, contributed by AndroidX WorkManager `2.7.0` through Digital Ink Recognition. It includes the app-specific signature permission `com.majkeylab.seliadocs.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
- The signed APK does not request billing, advertising-ID, camera, microphone, location, contacts, or broad storage permissions. Verify the merged APK, not only the app's source manifest, when reviewing permissions.
- Google Play's protected distribution adds its installer license check. The installed Play code 24 includes `com.android.vending.CHECK_LICENSE`; the locally signed upload artifact does not. Automatic installer protection remains enabled for the Alpha update.
- No first-party ads, analytics, telemetry, crash reporting, account, cloud sync, or remote database service.
- Notebook metadata, raw ink, recognition results, image OCR text, text, settings, selected image copies, and selected PDFs remain in private app storage. Digital ink recognition runs on-device after the model download; image OCR uses a bundled on-device model.
- Image OCR is enabled by default for newly imported images and can be disabled in Settings.
- Handwriting recognition is off by default, but the app checks the configured Digital Ink model status at startup.
- Google states that ML Kit Android SDKs collect device and app information, per-installation identifiers, performance metrics, API configuration, input and output sizes, event types, and error codes for diagnostics and usage analytics. Digital Ink Recognition also collects the configured language. Google encrypts this data in transit and states that it does not share the data with third parties. The publisher does not receive this SDK telemetry.
- Android backup is disabled.
- Photo Picker and document picker actions are initiated by the user.
- PDF and `.seliasheets` exports are written only to the user-selected destination.
- External legal, source, and support links open in the system browser after an explicit tap.

## Policy URL

https://majkey25.github.io/SeliaSheets/play/privacy/
