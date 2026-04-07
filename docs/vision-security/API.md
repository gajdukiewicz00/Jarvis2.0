# API

The service exposes its local API at:

- direct service: `http://127.0.0.1:8094/api/v1/vision-security`
- via gateway in local runtime: `https://127.0.0.1:18080/api/v1/vision-security`

The desktop UI uses the gateway path. In practice, the gateway path is the supported desktop/client entrypoint because it already carries Jarvis user authentication.

## Endpoints

### `GET /status`

Returns current service state, capability status, last decision, and effective config summary.

Example response:

```json
{
  "serviceStatus": "DEGRADED",
  "monitoringEnabled": true,
  "activeUserId": "owner",
  "ownerEnrolled": true,
  "lastDecision": "OWNER_PRESENT",
  "lastDecisionAt": "2026-04-07T12:00:04Z",
  "lastReason": "At least one detected face matched the enrolled owner profile",
  "lastFaceCount": 1,
  "unknownStreak": 0,
  "lastIncidentId": "20260407T115500Z-3a5e...",
  "incidentCount": 2,
  "camera": { "state": "AVAILABLE", "detail": "Camera backend V4L2" },
  "screenshot": { "state": "AVAILABLE", "detail": "Using gnome-screenshot" },
  "ocr": { "state": "UNAVAILABLE", "detail": "Install `tesseract-ocr` to enable OCR extraction" },
  "email": { "state": "AVAILABLE", "detail": "Configured for owner@example.com" },
  "gpu": {
    "preferGpu": false,
    "available": true,
    "activeBackend": "cpu",
    "detail": "NVIDIA GeForce RTX ... CPU OpenCV baseline remains active in the MVP service."
  },
  "config": {
    "checkIntervalMs": 2000,
    "debounceUnknownFrames": 3,
    "alertCooldownSeconds": 60,
    "storageRoot": "/home/user/.jarvis/data/vision-security",
    "emailRecipient": "owner@example.com",
    "ocrLanguage": "eng",
    "preferGpu": false,
    "displayServer": "x11"
  }
}
```

### `GET /config`

Returns the effective read-only config summary shown in the desktop UI.

### `POST /monitoring/start`

Starts scheduled monitoring for the authenticated user.

Request body:

```json
{}
```

Response: same model as `GET /status`

### `POST /monitoring/stop`

Stops scheduled monitoring and returns status scoped to the caller.

Request body:

```json
{}
```

Response: same model as `GET /status`

### `POST /enrollment/capture`

Captures owner enrollment samples from the webcam.

Request body:

```json
{
  "sampleCount": 6
}
```

Response:

```json
{
  "userId": "owner",
  "enrolledAt": "2026-04-07T12:05:30Z",
  "sampleCount": 6,
  "ownerThreshold": 49.7,
  "uncertainThreshold": 69.7,
  "sampleDirectory": "/home/user/.jarvis/data/vision-security/users/owner/enrollment/samples"
}
```

Behavior:

- requires at least 3 samples
- ignores frames until exactly one clean face is visible
- writes normalized grayscale samples and `profile.json`

### `POST /enrollment/reset`

Deletes the stored enrollment directory for the authenticated user.

Request body:

```json
{}
```

Response: same model as `GET /status`

### `GET /incidents?limit=20`

Returns recent incidents for the authenticated user, newest first.

Example response:

```json
[
  {
    "incidentId": "20260407T115500Z-3a5e...",
    "userId": "owner",
    "createdAt": "2026-04-07T11:55:00Z",
    "decision": "UNKNOWN_PERSON",
    "faceCount": 1,
    "reason": "Detected faces stayed outside the owner threshold",
    "semanticTags": ["DEVELOPMENT", "SENSITIVE"],
    "screenContext": {
      "activeWindowTitle": "IntelliJ IDEA",
      "activeProcessName": "idea",
      "ocrText": "token refresh ...",
      "semanticTags": ["DEVELOPMENT", "SENSITIVE"]
    },
    "stagePaths": {
      "originalImage": ".../original.png",
      "enhancedImage": ".../enhanced.png",
      "segmentationMask": ".../segmentation-mask.png",
      "cleanedMask": ".../cleaned-mask.png",
      "detectionResultImage": ".../detection-result.png",
      "finalDecisionImage": ".../final-decision.png"
    },
    "incidentDirectory": ".../incidents/20260407T115500Z-3a5e...",
    "webcamPhotoPath": ".../original.png",
    "screenshotPath": ".../screenshot.png",
    "ocrTextPath": ".../screen-ocr.txt",
    "emailDelivery": {
      "attempted": true,
      "sent": true,
      "message": "Alert email sent"
    }
  }
]
```

### `GET /incidents/{incidentId}`

Returns one incident record for the authenticated user.

### `POST /pipeline/capture`

Captures one frame and exports the classical CV pipeline stages without waiting for an alert.

Request body:

```json
{}
```

Response:

```json
{
  "userId": "owner",
  "createdAt": "2026-04-07T12:08:10Z",
  "outputDirectory": "/home/user/.jarvis/data/vision-security/users/owner/snapshots/20260407T120810Z-...",
  "pipelineResult": {
    "decision": "OWNER_PRESENT",
    "faceCount": 1,
    "reason": "At least one detected face matched the enrolled owner profile",
    "faces": [
      {
        "box": { "x": 101, "y": 64, "width": 158, "height": 158 },
        "verdict": "OWNER",
        "confidence": 43.2
      }
    ],
    "stagePaths": {
      "originalImage": ".../original.png",
      "enhancedImage": ".../enhanced.png",
      "segmentationMask": ".../segmentation-mask.png",
      "cleanedMask": ".../cleaned-mask.png",
      "detectionResultImage": ".../detection-result.png",
      "finalDecisionImage": ".../final-decision.png"
    },
    "rawFramePath": ".../original.png"
  }
}
```

### `POST /alerts/test`

Sends a test email to the configured recipient without creating an incident.

Response:

```json
{
  "attempted": true,
  "sent": true,
  "message": "Test alert sent"
}
```

## Enrollment and Incident Models

### Enrollment profile

- `userId`
- `enrolledAt`
- `sampleCount`
- `ownerThreshold`
- `uncertainThreshold`
- `sampleDirectory`

### Incident record

- identity: `incidentId`, `userId`, `createdAt`
- decision: `decision`, `faceCount`, `reason`
- context: `semanticTags`, `screenContext`
- evidence: `stagePaths`, `webcamPhotoPath`, `screenshotPath`, `ocrTextPath`, `incidentDirectory`
- delivery result: `emailDelivery`

## Health

`GET /actuator/health`

This is the diagnostics path for local runtime readiness. It reports capability state for camera, screenshot, OCR, email, GPU, and display server.
