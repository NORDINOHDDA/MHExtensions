# MHExtensions

Tachiyomi/Mihon extension repository for personal use, based on the [Keiyoushi](https://github.com/keiyoushi/extensions-source) build infrastructure.

## Extensions

| Extension | Language | Site | Status |
|-----------|----------|------|--------|
| **Comix** | All | [comix.to](https://comix.to) | Working |
| **ManhuaRMTL** | English | [manhuarmtl.com](https://manhuarmtl.com) | Working (with OCR text overlay) |

## Repository URL for Mihon

To use this repo in Mihon, add:

```
https://raw.githubusercontent.com/marbou92/MHRepo/main/repo.json
```

## Features

### Comix (`src/all/comixto/`)
- Reverse-engineered API with request signing and response decryption
- Image descrambling for tile-scrambled pages
- 10 extension settings (content rating, deduplication, score display, etc.)
- Comix-style description format with stars and bold info line

### ManhuaRMTL (`src/en/manhuarmtl/`)
- Madara-based theme with custom MRM selectors
- **OCR text overlay** — burns English MTL text onto raw images (the site serves raw images; English is a JS overlay fetched from `fetch-ocr.php`)
- Toggle between English (MTL overlay) and Raw images in settings
- NSFW content filter (hide/show adult content)
- Comix-style description format with stars and bold info line
- Custom filters: genres (include/exclude), status, sort, author, artist, release year

## Build & Publish

### Prerequisites

1. **GitHub Secrets** (in this repo's Settings → Secrets and variables → Actions):
   - `SIGNING_KEY` — base64-encoded `.jks` keystore file
   - `KEY_STORE_PASSWORD` — keystore password
   - `ALIAS` — key alias name
   - `KEY_PASSWORD` — key password
   - `REPO_PAT` — GitHub PAT with write access to `marbou92/MHRepo`
   - `SIGNING_FINGERPRINT_MANUAL` — SHA-256 fingerprint of your signing key (for cleanup mode)

2. **To get the signing fingerprint:**
   ```bash
   keytool -list -v \
     -keystore signingkey.jks \
     -storepass "YOUR_KEYSTORE_PASSWORD" \
     -alias "YOUR_ALIAS" \
     -keypass "YOUR_KEY_PASSWORD" \
     | grep "SHA256:"
   ```

### Publishing extensions

1. Make your changes to the extension source code
2. Bump `versionCode` in the extension's `build.gradle.kts`
3. Commit and push to `main`
4. Go to **Actions → Release & Publish → Run workflow**
5. Select `publish` mode and run

The workflow will:
- Build signed release APKs for all extensions in `settings.gradle.kts`
- Compute the signing key fingerprint
- Publish APKs, JARs, icons, and protobuf index (`index.pb`) to [MHRepo](https://github.com/marbou92/MHRepo)
- Purge the jsDelivr CDN cache

### Cleaning up orphaned extensions

If you rename or move an extension (e.g., from `src/all/` to `src/en/`), the old entry stays in MHRepo's index. To clean up:

1. Go to **Actions → Release & Publish → Run workflow**
2. Select `cleanup-dry-run` mode first to preview what will be deleted
3. If correct, run again with `cleanup` mode to actually delete

## Project structure

```
MHExtensions/
├── .github/
│   ├── scripts/
│   │   ├── publish-repo.py      # Publish & cleanup script (protobuf-based)
│   │   ├── index.proto          # Protobuf schema for Mihon v2 index
│   │   └── index.min.json       # Static legacy marker file
│   └── workflows/
│       └── release_publish.yml  # Build + publish + cleanup workflow
├── core/                        # Shared library code
├── compiler/                    # KSP annotation processor
├── lib/                         # Shared libraries (cryptoaes, i18n)
├── lib-multisrc/                # Multisrc themes (madara, etc.)
├── src/
│   ├── all/comixto/             # Comix extension
│   └── en/manhuarmtl/           # ManhuaRMTL extension
├── settings.gradle.kts          # Extension loading config
└── gradlew                      # Gradle wrapper
```

## Adding a new extension

1. Create `src/<lang>/<name>/` with the extension source
2. Add `loadIndividualExtension("<lang>", "<name>")` to `settings.gradle.kts`
3. Add the icon at `src/<lang>/<name>/res/mipmap-xhdpi/ic_launcher.png`
4. Commit, push, and run the publish workflow

## Credits

- Build infrastructure: [Keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source)
- Multisrc themes: [Keiyoushi](https://github.com/keiyoushi)
- App: [Mihon](https://github.com/mihonapp/mihon)

## License

See [LICENSE](LICENSE).
