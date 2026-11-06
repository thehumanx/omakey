# Releasing to Google Play

## Build
CI builds the bundle (`.github/workflows/play-bundle.yml`, run it from the Actions tab). Locally:
```bash
./gradlew :app:bundlePlayRelease      # app/build/outputs/bundle/playRelease/app-play-release.aab
./gradlew :app:assembleGithubRelease  # sideload APK, keeps the GitHub update checker
```
Bump `versionCode` in `app/build.gradle.kts` for every upload. For a signed CI build add repo
secrets `UPLOAD_KEYSTORE_B64` (`base64 -w0 keystore/<file>`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`.

## Signing (Play App Signing)
See the walkthrough in the Play Console under Setup → App signing. Summary:
- **Keep the current key as the app signing key** (so sideloaded GitHub users can update from
  Play and vice versa): choose "Upload a key exported from Java keystore", export with Google's
  PEPK tool, upload the zip. Then create a *separate* upload key for day-to-day uploads.
- Or let Google generate the app key and use your current key as the upload key; simplest, but
  Play and GitHub builds then have different signatures.

## Privacy policy URL
`https://github.com/thehumanx/omakey/blob/main/docs/play-store/privacy-policy.md`
(merge this branch to `main` first), or enable Settings → Pages → deploy from `main` /docs.

## Pricing — read before publishing
Google Play **does not allow converting a free app to paid**; paid→free is allowed but
irreversible. To charge for the first 7 days you must set the app to *Paid* before its first
publish (needs a payments profile/merchant account), then switch to Free later. Paid apps can't
use free trials, and the GitHub APK stays free (GPL).

## Upload path
Internal testing → Closed testing (new personal accounts: 12+ testers for 14 days) →
Production with staged rollout. Forms: Data safety, Content rating, Target audience, Ads (none);
answers in `listing.md`.
