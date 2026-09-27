# Presentation renderer build

Normal Android/Gradle builds use the checked-in `app/src/main/assets/presentation/renderer.js`; they do not require Node or npm.

To regenerate that asset, run these commands in `tools/presentation` with Node 18+ and Git:

```text
npm ci --ignore-scripts
npm test
npm run build
```

The private package pins `@aiden0z/pptx-renderer` 1.3.0 and esbuild 0.28.2 as build-only dependencies. `.npmrc` disables dependency lifecycle scripts; the build is run manually. Keep the platform-specific esbuild optional package installed. Do not use `--omit=optional`.

`build.mjs` checks the original standalone browser artifact's SHA-256 (`46b61afa1435de0c194f93324c9467ca392c891c6e07517727c8ffb5e51c376b`), stages it in an owned `.reference/tmp/presentation-build-*` directory, applies `docs/third-party/pptx-renderer-1.3.0.patch`, and transforms syntax with `target: chrome74`. It does not rebundle installed chart modules or other transitive dependencies. Legal comments stay inline. The build prepends `compat.js`, writes the asset, updates its NOTICE checksum, and removes its own temporary directory.

Edit the vendor patch or compatibility source, not the generated asset. The patch uses conventional `a/renderer.js` and `b/renderer.js` paths. The WebView still loads only the existing four allowlisted paths: `index.html`, `import.js`, `renderer.js`, and `source.pptx`.

## Chrome 74 compatibility audit

- Optional chaining and nullish coalescing are lowered by esbuild. Syntax lowering does not polyfill runtime APIs.
- `Promise.allSettled` requires Chrome 76. The conditional shim preserves input order, fulfilled/rejected outcomes, empty input, and iterator errors; native implementations remain untouched. See [V8's compatibility table](https://v8.dev/features/promise-combinators).
- Existing `flat`/`flatMap` and `Object.fromEntries` are available before Chrome 74; no shim is added. See [array methods](https://v8.dev/features/array-flat-flatmap) and [Object.fromEntries](https://v8.dev/features/object-fromentries).
- The import path uses supported `fetch`, `TextEncoder`, `AbortController`, `FontFaceSet.ready`, and image `decode()` APIs. Resize/IntersectionObserver paths retain upstream capability checks. Optional `Symbol.dispose` aliases are not used: the importer calls `.dispose()` explicitly.
- Audit found no calls to `replaceAll`, `matchAll`, `at`, `findLast`, `Object.hasOwn`, `Promise.any`, `structuredClone`, `WeakRef`, or `FinalizationRegistry`. `import.js` uses neither optional chaining nor `replaceChildren`.
- This does not provide graphical parity with current Chromium. Native MathML layout is absent in Chrome 74, and upstream 3D lighting overlays use the newer CSS `inset` shorthand. These remain format/fidelity limitations; use a source-exported PDF when exact equations/effects are required. PDF.js/Worker fallback stays disabled, and embedded fonts stay disabled by the host.

The Node checks exercise shim behavior, not Android rendering. Keep the real API 29/WebView 74 rendering tests enabled and verify the regenerated asset there before claiming runtime compatibility.
