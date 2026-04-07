# Demo

## Goal

Show the full local loop in 1 to 3 minutes:

- owner enrollment
- classical CV stage export
- live monitoring
- confirmed unknown incident
- evidence review in the desktop UI

## Pre-Demo Checklist

- Ubuntu desktop session is running
- webcam is connected and visible
- `vision-security-service` is up
- `xdotool` works in the current session
- optional: `tesseract-ocr` installed for OCR
- optional: SMTP configured for live email demo

## Recommended Demo Script

### 1. Show service status

- open the shell-native Vision Security page
- point out camera, OCR, screenshot, email, and GPU status
- show the check interval and debounce values

### 2. Enroll the owner

- click `Capture Owner Enrollment`
- keep only the owner in frame
- show that `Owner enrolled` becomes active

### 3. Export the report pipeline

- click `Export Pipeline Snapshot`
- open the output directory
- show:
  - `original.png`
  - `enhanced.png`
  - `segmentation-mask.png`
  - `cleaned-mask.png`
  - `detection-result.png`
  - `final-decision.png`

This covers the course/report requirement even before an alert.

### 4. Show stable owner behavior

- start monitoring
- keep the enrolled owner in frame
- show `OWNER_PRESENT`

### 5. Trigger an incident cleanly

- have the owner leave the frame
- have a second person sit alone in front of the camera
- wait for the debounce window to pass
- show the new incident in the list

### 6. Review evidence

- open the incident directory from the displayed path
- show webcam frame, screenshot, OCR text, and `incident.json`
- if SMTP is configured, show the received alert email or use `Send Test Alert`

## Clean Triggering Tips

- do not use owner + unknown together for the alert demo, because the MVP intentionally treats owner presence as non-alert
- keep the unknown person centered and reasonably frontal
- use moderate lighting
- avoid large reflections from the monitor
