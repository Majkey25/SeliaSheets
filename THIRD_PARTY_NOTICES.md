# Third-party notices

SeliaSheets includes open-source Android and Kotlin components, including AndroidX Activity, Compose, DataStore, Ink, Lifecycle, Room, Test, Kotlin Coroutines, and OkHttp. These components are distributed under their respective licenses, primarily Apache License 2.0.

Editor icons are adapted from Google Material Symbols © Google LLC
(https://github.com/google/material-design-icons), distributed under Apache License 2.0.

Google ML Kit Digital Ink Recognition and Text Recognition are provided by Google LLC. Their use is subject to the [Google ML Kit terms](https://developers.google.com/ml-kit/terms) and [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure).

Dependency versions are declared in [`app/build.gradle.kts`](app/build.gradle.kts). Launcher and website branding files are maintained in `branding/` and `site/assets/`.

PowerPoint conversion includes `@aiden0z/pptx-renderer` 1.3.0 and its bundled dependencies. See the [complete renderer notices and source links](app/src/main/assets/presentation/NOTICE.txt) and [license copies](app/src/main/assets/presentation/licenses). The [local patch](docs/third-party/pptx-renderer-1.3.0.patch) makes rendering errors reach the importer; it does not change the MPL-covered font decompressor. Embedded font loading is disabled. These notices are also available offline in Settings → App & privacy → Open-source licenses.
