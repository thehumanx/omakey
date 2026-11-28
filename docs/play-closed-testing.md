# Shipping a build to Play closed testing

A checklist for putting a new omakey build in front of closed testers. The GitHub release
(sideload APK) is separate and unaffected by any of this.

## 1. Build the bundle

```
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
./gradlew :app:bundlePlayRelease
```

The file is `app/build/outputs/bundle/playRelease/app-play-release.aab`. Play only accepts a
`versionCode` higher than every bundle uploaded before, on any track. Bump it in
`app/build.gradle.kts` for every upload, even one that is only a rebuild.

The bundle is signed with the key in `keystore/`. On Play that key is the **upload key**: Play App
Signing re-signs the app with Google's key before testers get it. Keep the upload key safe, and
never generate a new one (AGENTS.md invariant 4). If it is ever lost, Play support can reset the
upload key. That isn't possible for the GitHub APK's key, which is the same file.

R8 is on for release builds. The bundle carries its own deobfuscation map, so Play shows readable
crash stack traces with nothing extra to upload. That also clears the "no deobfuscation file"
warning.

The GitHub APK has no such home for its map. After each release build, copy
`app/build/outputs/mapping/sideloadRelease/mapping.txt` and
`app/build/outputs/mapping/playRelease/mapping.txt` into `mappings/v<version>/` (git-ignored). A
crash a tester pastes from the APK can only be decoded with the map from that exact build. Never
attach the map to a release: it undoes the obfuscation.

## 2. Create the release

1. Play Console → omakey → **Test and release → Testing → Closed testing**.
2. Open the track your testers are on, then **Create new release**.
3. **App bundles → Upload** and pick the `.aab`. Wait for the upload to finish processing. Errors
   show here, for example a versionCode that is too low.
4. **Release name**: Play fills in the version (e.g. `60 (6.0.0)`). Keep it.
5. **Release notes**: short, plain words, at most 500 characters, inside the language tags Play
   shows:
   ```
   <en-US>
   - Fixed a crash when opening emoji
   - One backspace deletes a whole emoji, flags and skin tones included
   - Tap a copied image in clipboard history to paste it into chat apps
   - Try your settings on the keyboard preview in Settings: popups, Shift, swipes, sound
   - The preview stays at the top while you scroll
   </en-US>
   ```
6. **Next** shows the review page. Warnings (yellow) don't block a release. Errors (red) do. Read
   each one anyway.
7. **Save**, then **Go to overview → Send changes for review** (on the Publishing overview page).
   Nothing reaches testers until this step.

## 3. Review and testers

- Review usually takes a few hours and can take a few days. You get an email when it's done, and
  the release goes out to testers by itself after that.
- Testers are managed in the track's **Testers** tab: an email list or a Google Group. Testers
  must open the **opt-in link** on that tab once, with the Google account on their phone, before
  the Play Store will offer them the app.
- Testers get the update like any other app update. Asking them to open the omakey listing in the
  Play Store and tap **Update** makes it immediate.
- To reach production, a new personal developer account needs at least 12 testers opted in for
  14 days in a row on a closed test before Play lets you apply for production access. Check
  **Publishing overview** for where you stand.

## 4. Things Play may ask about

- **Privacy policy URL**: the hosted copy of `docs/privacy.md`.
- **Data safety form**: omakey collects and shares nothing. Language packs are downloaded on
  request and nothing is uploaded.
- **"Edge-to-edge may not display for all users"**: an advisory for apps targeting Android 15.
  Settings already draws edge to edge. If Play keeps showing it after 5.7.0, open **View details**:
  it names the API it found. If that API comes from a library (for example
  `androidx.activity`), updating that library is the fix.
