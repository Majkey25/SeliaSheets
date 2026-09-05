# Google Play Console checklist

## App setup

- Package: `com.majkeylab.seliadocs`
- App name: `SeliaSheets`
- Default language: English (United States)
- App or game: App
- Free or paid: Free
- Category: Productivity
- Contact email: `majkeylab@gmail.com`
- Privacy policy: `https://majkey25.github.io/SeliaSheets/privacy/`

## Declarations

- Ads: No
- App access: No restrictions
- Data safety: Data collected through Google ML Kit; not shared. Use [`DATA_SAFETY.md`](DATA_SAFETY.md)
- Target audience: 13 and older
- Content rating: utility/productivity; no violence, sexuality, gambling, controlled substances, or user-generated online content
- News, health, financial, government, and COVID-19 declarations: No / not applicable

## Store assets

- 512 × 512 app icon
- 1024 × 500 feature graphic
- At least two phone screenshots
- Tablet screenshots
- English short and full descriptions from [`STORE_LISTING.md`](STORE_LISTING.md)

## Release

1. Enroll in Play App Signing.
2. Update Data safety from [`DATA_SAFETY.md`](DATA_SAFETY.md) and confirm that the hosted privacy policy matches it.
3. Upload the signed `app-release.aab` with version code 13 and version name `0.6.0-beta.1` to the existing closed testing track.
4. Add the English release notes from [`STORE_LISTING.md`](STORE_LISTING.md).
5. Confirm the package, version, target SDK, tester list, and dashboard status before publishing the closed release.
6. Do not publish to production until Play reports zero blocking tasks and the hosted privacy URL is live.
