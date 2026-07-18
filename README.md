# Droid-QR-Data-Exchange

Android app that transfers files via QR codes: the input is bundled into a tar archive,
compressed with LZMA, encrypted, and encoded as one or more QR codes. Intended for offline
transfer where no network connection is available or desired.

This is a native Android port of [py-qr-data-exchange](https://github.com/vaultecki/py-qr-data-exchange),
wire-format compatible with it (same Argon2i key derivation, NaCl SecretBox encryption,
tar/LZMA/msgpack format) since both use libraries that wrap the same underlying reference
implementations.

## Features

- Encryption: NaCl SecretBox (XSalsa20-Poly1305) with Argon2i password-based key derivation
- Compression: LZMA compresses the bundled content before encryption
- Bundles any number of files and/or whole directories (recursively) into one transfer
- Automatic multi-part splitting: large payloads are split across as many QR codes as needed
- Each QR code is encrypted independently (own salt, own Argon2i derivation, own SecretBox);
  part number and total-part count are only visible after successful decryption
- Multi-part QR codes can be scanned/loaded in any order
- Three ways to read a QR code: live camera scan, picking an image/text file, or pasting the
  QR's text directly

## Requirements

- Android 8.0 (API 26) or higher
- A camera, for live QR scanning (optional -- generating and reading via file/text still work
  without one)

## Installation

### Prebuilt APK

Grab `app-debug.apk` from a build (see below) and install it:

```bash
adb install -r app-debug.apk
```

### Build from source

Requires JDK 17+ and the Android SDK (command-line tools or Android Studio).

```bash
git clone git@github.com:vaultecki/DroidQRDataExchange.git
cd DroidQRDataExchange
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and hit **Run** with a device/emulator selected.

## Usage

The app has two tabs, "QR erstellen" (generate) and "QR einlesen" (read) -- each keeps its own
password field, so switching tabs never mixes up which password applies to which action.

### Encrypting files/folders to QR codes ("QR erstellen")

1. Enter a password (required; no length limit).
2. Select input:
   - **Dateien wählen**: one or more files (multi-select supported)
   - **Ordner wählen**: a whole directory, added recursively
3. Tap **QR erzeugen**. Each QR code involves its own key derivation, so payloads needing many
   QR codes take correspondingly longer than a single one.
4. The result screen shows the generated QR code(s), one per page:
   - Swipe or use the page indicator to browse parts
   - **Aktuellen speichern**: save the currently displayed QR code as a PNG
   - **Alle speichern**: save all QR codes to a chosen folder, including `.txt` files with the
     QR text (these can be re-imported directly when decrypting)

### Decrypting QR codes ("QR einlesen")

1. Enter the password used for encryption.
2. Add parts, in any order, via any combination of:
   - **Kamera-Scan**: point the camera at a QR code; detected codes are added automatically
   - **Datei(en) auswählen**: pick QR code images (`.png`/`.jpg`) and/or `.txt` files (as saved
     by "Alle speichern"); multi-select supported
   - **QR-Text einfügen**: paste a QR code's text and tap "Hinzufügen"; pasting multiple
     concatenated texts at once (base64 blobs ending in `==`) is split automatically
   - The status line shows "X/Y Teile geladen" once at least one part has decrypted (Y is only
     known at that point, since it's encrypted)
3. **Entschlüsseln und speichern** is enabled once all parts 1..Y are loaded.
4. Tap it and choose an output folder; all recovered files and folder structure are extracted
   there.

A part that fails to decrypt (wrong password, corrupted, or from a different transfer) is
rejected with an explanation when added, rather than only failing at the final decrypt step.

## How it works

```
Generating:
  File(s)/Folder(s) -> tar -> LZMA compress -> split into chunks
    -> per chunk: fresh random salt -> Argon2i key derivation -> NaCl SecretBox encrypt
    -> base64 -> QR code image

Reading:
  QR code(s) -> base64 decode -> per part: own salt -> Argon2i key derivation -> NaCl SecretBox decrypt
    -> validate all parts agree on total count, none missing
    -> sort by part number, concatenate -> LZMA decompress -> tar extract
```

Part number and total-part count live inside the ciphertext, so nothing about how a payload was
split is visible without the password. See the
[py-qr-data-exchange README](https://github.com/vaultecki/py-qr-data-exchange) for the full wire
format and design rationale, which this app follows.

### Errors

| Error | Meaning |
|---|---|
| "Teil X wurde bereits mit anderem Inhalt geladen" | A part number was scanned/loaded twice with different content -- likely two different transfers got mixed |
| "Missing parts: [...]" | Not all parts were loaded/scanned yet |
| "Inconsistent total_parts across parts" | Parts disagree on total count, likely mixed from different transfers |
| "Kann nicht entschlüsseln" | A part's decryption/authentication failed -- wrong password or corrupted data |
| "Unsafe path in archive" | A recovered archive entry tried to escape the output folder; extraction was refused |

## Security

- Algorithm: NaCl SecretBox (XSalsa20 + Poly1305)
- Key derivation: Argon2i, run independently per QR code
- Salt: unique random salt per QR code
- Authentication: MAC per part, detects tampering
- Use a long password or passphrase; there is no password recovery
- Don't store the password together with the QR codes

## Development

### Project structure

```
app/src/main/java/net/vaultcity/droid_qr_data_exchange/
├── crypto/CryptoUtils.kt      # Argon2i + NaCl SecretBox (lazysodium-android)
├── format/QrMultiPart.kt      # tar/LZMA/msgpack packing, per-part encryption, reassembly
├── service/QrService.kt       # QR image generation (ZXing) and static-image decode (ML Kit)
├── data/SafInput.kt           # Storage-Access-Framework file/folder input helpers
├── data/SafOutput.kt          # SAF output helpers (extracted files, saved QR images)
├── viewmodel/                 # GenerateViewModel, ReadViewModel
└── ui/                        # Compose screens: Generate, QR result, Read, camera scanner
```

### Running tests

```bash
./gradlew testDebugUnitTest          # pure-JVM logic
./gradlew connectedDebugAndroidTest  # crypto/format round-trip tests (needs a device/emulator)
```

## License

Copyright 2026 ecki
SPDX-License-Identifier: Apache-2.0
