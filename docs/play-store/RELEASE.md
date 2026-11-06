# Releasing to Google Play

## One-time setup
1. Play Console → Create app (`dev.omakey.app`), then Setup → **Play App Signing**.
2. Choose **upload the existing signing key** and export it with Google's PEPK tool from your
   current `keystore/` key, so sideloaded users can still update across channels.
   (If you prefer, let Google generate a new app key; then Play and GitHub builds have different
   signatures and can't upgrade over each other.)
3. Keep `keystore/keystore.properties` locally (it's gitignored).

## Build
```bash
./gradlew :app:bundlePlayRelease      # app/build/outputs/bundle/playRelease/app-play-release.aab
./gradlew :app:assembleGithubRelease  # sideload APK, keeps the GitHub update checker
```
Bump `versionCode` in `app/build.gradle.kts` for every upload.

## Upload path
Internal testing → Closed testing (new personal accounts: 12+ testers for 14 days) →
Production with staged rollout. Fill Data safety, Content rating, Target audience and
Ads declarations using `listing.md`.
