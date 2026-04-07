# Screen Analysis

## MVP Scope

The MVP screen-context pipeline is intentionally lightweight and local:

- full-screen screenshot
- OCR text extraction
- active window title
- active process name
- rule-based semantic tags

No VLM is required in the current implementation.

## Screenshot Capture

Backend order:

1. `gnome-screenshot`
2. `scrot`
3. `import` from ImageMagick
4. Java `Robot` fallback in a non-headless session

The screenshot is saved as `screenshot.png` inside the incident directory.

## OCR

OCR is performed by the local `tesseract` CLI:

```text
tesseract <image> stdout -l <language> --psm 6
```

Current config:

- language key: `vision-security.screen.ocr-language`
- default language: `eng`

If `tesseract` is missing, the service still writes `screen-ocr.txt` with a clear failure note instead of pretending OCR succeeded.

## Active Window / Process Collection

The service uses `xdotool`:

- `xdotool getactivewindow getwindowname`
- `xdotool getactivewindow getwindowpid`

Then it resolves the process name with:

```text
ps -p <pid> -o comm=
```

This is most reliable on X11. Wayland support is limited in the MVP.

## Semantic Tags

The tagger is deterministic and rule-based. Current tags:

- `DEVELOPMENT`
- `COMMUNICATION`
- `EMAIL`
- `FINANCE`
- `SENSITIVE`
- `DOCUMENTS`
- `MEDIA`
- `GENERAL_DESKTOP`

### Rule Sources

Tags are derived from a combined lowercase string of:

- active window title
- active process name
- OCR text

### Example Triggers

- `DEVELOPMENT`: `intellij`, `terminal`, `bash`, `pom.xml`, `github`, `git`
- `COMMUNICATION`: `telegram`, `discord`, `slack`, `whatsapp`, `signal`, `teams`
- `EMAIL`: `gmail`, `outlook`, `mail`, `inbox`, `subject:`, `compose`
- `FINANCE`: `invoice`, `payment`, `bank`, `wallet`, `card`, `iban`
- `SENSITIVE`: `password`, `secret`, `token`, `ssh`, `private key`, `api key`
- `DOCUMENTS`: `spreadsheet`, `slides`, `document`, `.pdf`, `excel`
- `MEDIA`: `youtube`, `netflix`, `spotify`, `twitch`, `steam`

If no rule matches, the tagger returns `GENERAL_DESKTOP`.

## Limitations

- OCR quality depends on screenshot quality and installed language packs
- active-window metadata depends on local session tooling
- tags are coarse and intentionally conservative
- screen content is not semantically “understood” beyond OCR + rules in the MVP
