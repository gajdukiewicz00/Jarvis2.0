# Enrollment

## Purpose

Enrollment creates the local owner profile used by the binary verifier. The service does not learn multiple identities in the MVP. It stores one owner profile per authenticated Jarvis user.

## How It Works

`POST /api/v1/vision-security/enrollment/capture`

Flow:

1. The service opens the webcam.
2. It waits until exactly one clear face is visible.
3. It captures normalized grayscale face crops.
4. It stores the samples under the user’s enrollment directory.
5. It calibrates thresholds from the captured samples.

## Default Capture Parameters

- sample count: 6
- minimum allowed: 3
- spacing between successful samples: 700 ms
- capture timeout: 20 s
- normalized face size: 160 x 160

## Threshold Calibration

The service trains an LBPH recognizer using the captured samples and performs leave-one-out holdout scoring:

1. hold out one sample
2. train on the remaining owner samples
3. predict the held-out sample
4. record the confidence
5. take the max holdout confidence
6. add `owner-threshold-margin`

The uncertain threshold is:

```text
ownerThreshold + uncertainThresholdMargin
```

This keeps enrollment specific to the actual captured samples instead of relying only on a hardcoded threshold.

## Recommended Capture Procedure

- sit alone in frame
- keep your face mostly frontal
- avoid strong monitor glare
- keep the whole face visible
- let the service sample across a few natural micro-movements instead of staying frozen

## Practical Recommendations

- capture 6 to 8 samples for the initial setup
- if the owner is often flagged `UNCERTAIN`, re-enroll in better light
- if another person is frequently classified too close to owner confidence, re-enroll with more stable framing and lighting

## Stored Data

Per user:

- `enrollment/profile.json`
- `enrollment/samples/sample-01.png`, `sample-02.png`, ...

The stored images are normalized grayscale face crops, not full webcam frames.
