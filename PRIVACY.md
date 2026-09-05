# SeliaSheets privacy policy

Effective date: September 5, 2026

SeliaSheets is an offline-first Android notebook published by Majkey25. This policy explains what the app processes and how users control their content.

## Data collection and sharing

SeliaSheets has no first-party ads, accounts, cloud sync, analytics, telemetry, crash reporting, or sale of data. Notebook content is not uploaded or shared.

SeliaSheets uses two Google ML Kit features. Image text recognition is enabled by default and uses the bundled Latin Text Recognition model when the user imports an image. Users can disable **Search text in imported images** in Settings. Handwriting recognition is off by default and requires the user to enable it and download a language model. Both features process their input and output on-device. Notebook content, imported images, raw ink, OCR text, and recognition results are not sent to Google.

Google states that ML Kit Android SDKs collect device and app information, per-installation identifiers, performance metrics, API configuration, input and output sizes, event types, and error codes for diagnostics and usage analytics. Digital Ink Recognition also collects the configured language. Google encrypts this data in transit and states that it does not share the data with third parties. See Google's [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure) and [ML Kit terms](https://developers.google.com/ml-kit/terms).

## Data stored on the device

The app stores the following only in private Android app storage:

- notebook titles, covers, pages, paper choices, and settings;
- stylus and finger stroke coordinates, pressure, tilt, orientation, brush, and color;
- text, shape, arithmetic, handwriting-recognition results, and image OCR text;
- copies of images explicitly selected through Android Photo Picker;
- PDF files explicitly selected through Android's document picker.

Android backup is disabled for SeliaSheets. The app does not read unrelated photos or files.

## User-directed exports and links

PDF and `.seliasheets` backup export write to destinations selected by the user through Android's system document picker. SeliaSheets does not upload exported files.

Privacy policy, source-code, and optional Buy Me a Coffee links open only after a user action and are handled by the user's browser. Handwriting model downloads and the ML Kit SDK disclosure use network access as described above. The destination website's own privacy policy applies after it opens.

## Retention and deletion

Notebook data remains on the device until the user deletes it or uninstalls the app. Moving a notebook to trash is reversible. Permanent deletion removes its database content and unreferenced private assets. Uninstalling SeliaSheets removes its private app data according to Android platform behavior.

## Security

SeliaSheets uses Android private app storage, validates imported images, validates backup archives, and renders imported PDFs in an isolated process. The app does not request broad storage access. Users should protect device access and export sensitive notebooks only to trusted locations.

## Children

SeliaSheets is a general productivity tool and is not directed to children under 13.

## Changes

Material policy changes will be published in this repository and on the hosted privacy-policy page with an updated effective date.

## Contact

For privacy questions, email [majkeylab@gmail.com](mailto:majkeylab@gmail.com) or open an issue at [github.com/Majkey25/SeliaSheets](https://github.com/Majkey25/SeliaSheets/issues).
