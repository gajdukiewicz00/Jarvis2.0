# Runtime

## Supported Environment

- Ubuntu local workstation
- Java 21
- webcam accessible through OpenCV / V4L2
- desktop session available for screenshots and active-window lookup

The MVP is honest about platform assumptions. The screen-context path is best on X11. Wayland is detected, but active-window/process collection is still limited by the local CLI tools in use.

## Local Dependencies

### Required for core monitoring

- webcam device
- Java 21
- OpenCV native runtime via Maven dependency

### Required for richer evidence

- `xdotool` for active window title and PID
- one screenshot backend:
  - `gnome-screenshot`
  - `scrot`
  - `import`
  - or Java `Robot` fallback in a non-headless session

### Optional

- `tesseract-ocr` for OCR
- SMTP server reachable through `spring.mail.*`
- `nvidia-smi` for GPU visibility reporting

## Default Storage

Default root:

```text
${HOME}/.jarvis/data/vision-security
```

Per-user layout:

```text
users/<sanitized-user-id>/
  enrollment/
    profile.json
    samples/
  incidents/
  snapshots/
```

## Run Paths

### Standalone service

```bash
SERVICE_JWT_SECRET=12345678901234567890123456789012 \
SPRING_PROFILES_ACTIVE=dev \
mvn -q -pl apps/vision-security-service spring-boot:run
```

Reason:

- `apps/jarvis-common` fails fast unless `service.jwt.secret` or `jwt.secret` is present
- a direct standalone boot without that secret does not start successfully

### Full local Jarvis runtime

```bash
scripts/runtime-up.sh
```

The runtime scripts now include `vision-security-service` as a core local service.

## Useful Verification Commands

```bash
mvn -q -pl apps/vision-security-service test
```

```bash
curl -s http://127.0.0.1:8094/actuator/health | jq
```

For authenticated desktop use, prefer the gateway path:

```bash
curl -k -H "Authorization: Bearer <token>" https://127.0.0.1:18080/api/v1/vision-security/status
```

Direct `/api/v1/vision-security/*` access is still subject to the service security chain and should be treated as an authenticated path.

## Environment Variables

### Service / runtime

- `JARVIS_VISION_SECURITY_PORT`
- `JARVIS_VISION_SECURITY_SSL_ENABLED`
- `JARVIS_VISION_SECURITY_KEYSTORE_PATH`
- `JARVIS_VISION_SECURITY_KEYSTORE_PASSWORD`
- `JARVIS_VISION_SECURITY_KEYSTORE_TYPE`
- `VISION_SECURITY_ENABLED`
- `VISION_SECURITY_URL`

### Monitoring

- `VISION_SECURITY_CHECK_INTERVAL_MS`
- `VISION_SECURITY_DEBOUNCE_UNKNOWN_FRAMES`
- `VISION_SECURITY_ALERT_COOLDOWN_SECONDS`

### Camera / enrollment / verification

- `VISION_SECURITY_CAMERA_DEVICE_INDEX`
- `VISION_SECURITY_CAMERA_WIDTH`
- `VISION_SECURITY_CAMERA_HEIGHT`
- `VISION_SECURITY_ENROLLMENT_SAMPLE_COUNT`
- `VISION_SECURITY_ENROLLMENT_CAPTURE_TIMEOUT_SECONDS`
- `VISION_SECURITY_ENROLLMENT_SAMPLE_SPACING_MS`
- `VISION_SECURITY_FACE_SIZE`
- `VISION_SECURITY_OWNER_THRESHOLD_MARGIN`
- `VISION_SECURITY_UNCERTAIN_THRESHOLD_MARGIN`
- `VISION_SECURITY_FALLBACK_OWNER_THRESHOLD`
- `VISION_SECURITY_FALLBACK_UNCERTAIN_THRESHOLD`
- `VISION_SECURITY_MIN_DETECTION_AREA_RATIO`

### Storage / OCR / GPU

- `VISION_SECURITY_STORAGE_ROOT`
- `VISION_SECURITY_MAX_INCIDENTS_PER_USER`
- `VISION_SECURITY_OCR_LANGUAGE`
- `VISION_SECURITY_PREFER_GPU`

### Email

- `VISION_SECURITY_EMAIL_RECIPIENT`
- `VISION_SECURITY_EMAIL_FROM`
- `VISION_SECURITY_EMAIL_SUBJECT_PREFIX`
- `SPRING_MAIL_HOST`
- `SPRING_MAIL_PORT`
- `SPRING_MAIL_USERNAME`
- `SPRING_MAIL_PASSWORD`
- `SPRING_MAIL_SMTP_AUTH`
- `SPRING_MAIL_SMTP_STARTTLS_ENABLE`

## Troubleshooting

### Camera unavailable

- verify the device exists
- check that no other process is holding the webcam
- confirm `VISION_SECURITY_CAMERA_DEVICE_INDEX`

### OCR unavailable

- install `tesseract-ocr`
- optionally install language packs matching `VISION_SECURITY_OCR_LANGUAGE`

### No active window or process info

- check that `xdotool` is installed
- expect reduced behavior on Wayland sessions

### Screenshot capture fails

- install `gnome-screenshot` or `scrot`
- confirm the service is running inside a real desktop session
- Java `Robot` fallback will not work in headless mode

### Email alerts not sending

- set `VISION_SECURITY_EMAIL_RECIPIENT`
- configure `spring.mail.*`
- use `POST /api/v1/vision-security/alerts/test` before relying on live alerts
