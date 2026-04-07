# Incidents

## When An Incident Is Created

An incident is created only after:

- the pipeline decision is `UNKNOWN_PERSON`
- the same unknown state has been confirmed for the configured number of consecutive frames
- the alert cooldown has elapsed

The default debounce is 3 frames at a 2 second interval, so the first alert normally requires about 6 seconds of stable unknown presence.

## What Gets Stored

For each confirmed incident, the service stores:

- `incident.json`
- `original.png`
- `enhanced.png`
- `segmentation-mask.png`
- `cleaned-mask.png`
- `detection-result.png`
- `final-decision.png`
- `screenshot.png` when screen capture succeeds
- `screen-ocr.txt` when OCR is attempted

The incident JSON also stores:

- decision
- reason
- face count
- semantic tags
- active window title
- active process name
- screenshot/webcam/OCR paths
- email delivery result

## Directory Structure

Example:

```text
~/.jarvis/data/vision-security/
  users/owner/
    incidents/
      20260407T115500Z-3a5ef4ef-.../
        incident.json
        original.png
        enhanced.png
        segmentation-mask.png
        cleaned-mask.png
        detection-result.png
        final-decision.png
        screenshot.png
        screen-ocr.txt
```

## Retention

Retention is enforced per user with `vision-security.storage.max-incidents-per-user`.

Default:

```text
50 incidents per user
```

When a new incident is saved, the oldest incident directories beyond that limit are deleted.

## Incident Record Semantics

- `webcamPhotoPath` points to the saved original frame
- `stagePaths` points to all intermediate classical CV exports
- `screenContext.ocrText` stores the extracted OCR content or a failure message
- `emailDelivery` captures whether SMTP was attempted and whether it succeeded

## Snapshot vs Incident

The service also writes non-alert snapshots through `POST /pipeline/capture`.

Difference:

- snapshots are for demos and report assets
- incidents are for confirmed unknown detections
