# Google Play data safety

Working answers for version `0.4.1-beta.1` (`com.majkeylab.seliadocs`). Recheck them whenever dependencies or behavior change.

## Collection and sharing

- Does the app collect any required user data types? **Yes** — through Google ML Kit Digital Ink Recognition.
- Does the app share user data? **No**
- Is all collected user data encrypted in transit? **Yes**
- Can users request data deletion? **No publisher-managed deletion request is offered for Google-held ML Kit SDK metrics.** Users can permanently delete local notebook data or uninstall the app.
- Independent security review badge: **No**

## Required collected data declarations

Declare these as collected, required, used for Analytics, and not shared:

- Device or other IDs
- App interactions
- Diagnostics

Google's ML Kit disclosure also covers device and app information, configured language, errors, and performance and usage metrics. Do not declare Google-held SDK metrics as ephemeral or publisher-deletable.

## App behavior supporting these answers

- `INTERNET` and `ACCESS_NETWORK_STATE` permissions support the explicit Google ML Kit language-model download and SDK disclosure.
- No first-party ads, analytics, telemetry, crash reporting, account, cloud sync, or remote database service.
- Notebook metadata, raw ink, recognition results, text, settings, selected image copies, and selected PDFs remain in private app storage; recognition runs on-device after the model download.
- Google states that ML Kit may send device and app information, per-installation identifiers, app interactions, diagnostics, configured language, errors, and performance and usage metrics to Google over encrypted transport.
- Android backup is disabled.
- Photo Picker and document picker actions are initiated by the user.
- PDF and `.seliasheets` exports are written only to the user-selected destination.
- External legal, source, and support links open in the system browser after an explicit tap.

## Policy URL

https://majkey25.github.io/SeliaSheets/privacy/
