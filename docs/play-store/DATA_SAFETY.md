# Google Play data safety

Answers for version `0.2.1-beta.1` (`com.majkeylab.seliadocs`). Recheck them whenever dependencies or behavior change.

## Collection and sharing

- Does the app collect or share any required user data types? **No**
- Is all user data encrypted in transit? **Not applicable; the app does not transmit user data**
- Can users request data deletion? **Yes, directly in the app by permanent notebook deletion, or by uninstalling the app**
- Independent security review badge: **No**

## App behavior supporting these answers

- No `INTERNET` permission.
- No ads, analytics, telemetry, crash reporting, account, cloud, or remote database SDK.
- Notebook metadata, ink, text, settings, selected image copies, and selected PDFs remain in private app storage.
- Android backup is disabled.
- Photo Picker and document picker actions are initiated by the user.
- PDF and `.seliasheets` exports are written only to the user-selected destination.
- External legal, source, and support links open in the system browser after an explicit tap.

## Policy URL

https://majkey25.github.io/SeliaSheets/privacy/
