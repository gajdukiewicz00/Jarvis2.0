# Failure Cases

## Low Light

- Effect: darker frames reduce contrast and increase noisy masks
- Current handling: gamma correction and histogram equalization improve visibility, but cannot fully recover severe underexposure
- Likely outcome: more `UNCERTAIN` or missed detections

## Blur

- Effect: motion blur weakens Haar cascade detection and LBPH consistency
- Current handling: mild Gaussian blur is for denoising, not motion deblurring
- Likely outcome: `NO_FACE` or `UNCERTAIN`

## Partial Occlusion

- Effect: masks, eyes, hands, or hair blocking key facial regions reduce recognition quality
- Current handling: none beyond face-detection fallback
- Likely outcome: `UNCERTAIN`

## No Face

- Effect: nobody is in front of the machine or the face is fully outside frame
- Current handling: explicit `NO_FACE` state
- Alert behavior: no alert

## Owner + Unknown In Frame

- Effect: two or more people are visible, including the enrolled owner
- Current handling: if any face is classified as owner, final decision becomes `OWNER_PRESENT`
- Alert behavior: no alert by design in the MVP

## Side Angle

- Effect: large yaw/pitch changes reduce both detection stability and LBPH similarity
- Current handling: none beyond enrollment guidance and threshold calibration
- Likely outcome: `UNCERTAIN`

## Monitor Glare

- Effect: glare changes apparent skin regions and adds bright artifacts
- Current handling: enhancement improves contrast, but glare still damages segmentation and OCR
- Likely outcome: unstable masks and weaker screenshots for OCR

## OCR Limitations

- Effect: small fonts, dark themes, low contrast, or unsupported language packs reduce OCR quality
- Current handling: Tesseract `--psm 6`, configurable language, honest failure file when OCR is unavailable
- Likely outcome: incomplete or noisy `screen-ocr.txt`
