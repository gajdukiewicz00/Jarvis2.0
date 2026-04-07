# Report Draft

## Title

Jarvis Vision Security: A Local Owner Verification and Incident Capture Module for Ubuntu Workstations

## 1. Problem Description

The goal of this project was to build a local workstation security module for Jarvis that determines whether the person in front of the computer is the enrolled device owner. When an unknown person is confirmed, the system must capture local evidence and generate an actionable alert. The implementation target was an Ubuntu desktop environment, with the service running locally on the same machine it protects.

The project was also constrained by a computer-vision course requirement: the final implementation could not rely only on a black-box recognizer. It had to expose a visible classical image-processing pipeline with intermediate outputs for enhancement, segmentation, cleaning, detection, and final decision rendering.

## 2. System Design

The final implementation uses a dedicated local service, `vision-security-service`, integrated into the existing Jarvis repository. The service is exposed through the Jarvis API gateway and monitored from a shell-native JavaFX page in the desktop client.

The runtime loop samples the webcam every two seconds. Each frame is processed by a classical CV branch and then passed to a lightweight local recognizer. The classical branch improves image quality, generates a segmentation mask, removes noise, detects candidate face regions, and exports intermediate images. The recognizer uses local LBPH face recognition against an enrolled owner profile. The service then emits one of four states: `OWNER_PRESENT`, `UNKNOWN_PERSON`, `NO_FACE`, or `UNCERTAIN`.

To avoid noisy alerts, the system uses a debounce mechanism that requires multiple consecutive `UNKNOWN_PERSON` frames before an incident is created. If the owner is visible in the same frame as another person, the final state remains non-alerting because owner presence takes priority in the MVP decision rule.

## 3. Methods

### 3.1 Enhancement

The enhancement stage applies gamma correction, grayscale conversion, histogram equalization, and Gaussian blur. Gamma correction improves darker webcam frames, while histogram equalization expands contrast for downstream detection. The blurred grayscale image is then used both for face detection and normalized face extraction.

Figure 1 should show `original.png`.

Figure 2 should show `enhanced.png`.

### 3.2 Segmentation

The segmentation stage converts the enhanced frame into YCrCb color space and applies a skin-tone threshold. This produces a coarse foreground mask that highlights plausible facial regions and suppresses some background clutter.

Figure 3 should show `segmentation-mask.png`.

### 3.3 Cleaning

The mask is cleaned with morphological opening followed by closing using an elliptical kernel. Opening removes small isolated blobs, while closing reconnects fragmented foreground regions. This produces a more stable mask for visualization and contour analysis.

Figure 4 should show `cleaned-mask.png`.

### 3.4 Detection

The system uses two explainable detection outputs:

- contour boxes derived from the cleaned mask
- Haar cascade face boxes on the enhanced grayscale frame

The contour pass supports the report requirement for classical region detection, while the Haar cascade provides the practical face-localization path used by the verifier. Detected regions smaller than the configured minimum area ratio are discarded.

Figure 5 should show `detection-result.png`.

### 3.5 Decision

Each detected face is normalized to a fixed grayscale size and scored against the owner profile with LBPH face recognition. The owner threshold is calibrated from the enrolled samples with leave-one-out holdout scoring. If any face matches the owner threshold, the frame is classified as `OWNER_PRESENT`. If all faces remain outside the owner threshold and none are merely uncertain, the result becomes `UNKNOWN_PERSON`. The final overlay includes bounding boxes, per-face verdicts, confidence values, and the final frame decision.

Figure 6 should show `final-decision.png`.

## 4. Incident Capture and Context Enrichment

When the debounce policy confirms an unknown person, the service stores a local incident record. The record contains the exported webcam stages, a desktop screenshot, OCR text extracted from the screenshot, the active window title, the active process name, rule-based semantic tags, and the email delivery result. OCR is implemented with the local `tesseract` CLI, while active window and process metadata are collected through `xdotool` and `ps`.

This evidence is saved locally before any email is sent, which ensures the service remains useful even when SMTP is unavailable.

## 5. Results

The implementation satisfies the requested product behavior:

- local Ubuntu service
- separate Jarvis module
- desktop UI integration
- periodic 2 second monitoring
- owner enrollment
- owner-vs-unknown verification
- multi-frame alert debounce
- evidence capture on confirmed unknown
- OCR and screen-context enrichment
- exportable intermediate CV stages for the report/demo

The system also exposes a manual snapshot endpoint that exports all pipeline stages on demand. This makes it possible to prepare report figures without waiting for a live unknown-person incident.

## 6. Failure Cases

The main failure cases observed or expected in this MVP are:

- low light reduces both segmentation quality and recognition stability
- blur from fast movement weakens face localization
- side angles and partial occlusion increase `UNCERTAIN` outcomes
- monitor glare can distort skin-mask segmentation
- OCR quality depends on text size, contrast, and installed language packs
- owner + unknown in one frame intentionally does not alert in the MVP

These cases are documented separately in `FAILURE_CASES.md`.

## 7. Conclusion

The implemented module delivers a complete local workstation-monitoring path inside the real Jarvis repository without introducing a remote CV dependency. It combines a classical, report-friendly image-processing pipeline with a lightweight owner verifier and an evidence-first incident flow. While the MVP does not yet include spoof detection, a stronger multi-person model, or richer Wayland support, it already satisfies the functional and course-report requirements with reproducible local outputs.
