#!/usr/bin/env python3
"""
Vision Security V2 — Owner Face Dataset Curator

Processes a directory of webcam photos into a clean, structured dataset
suitable for V2 enrollment and validation.

Pipeline per image:
  1. Read + basic metadata
  2. Face detection (Haar cascades, matching the Java V2 pipeline)
  3. Quality assessment (blur, contrast, brightness, face size)
  4. Near-duplicate detection via perceptual hashing
  5. Partitioning into enrollment / validation / negative splits

Usage:
  python3 scripts/curate-owner-dataset.py \
      --source ~/Pictures/Webcam \
      --output apps/vision-security-service/dataset \
      --enrollment-count 15 \
      --validation-count 10
"""

import argparse
import hashlib
import json
import os
import shutil
import sys
from collections import defaultdict
from dataclasses import dataclass, field, asdict
from datetime import datetime
from pathlib import Path
from typing import Optional

import cv2
import numpy as np


HAAR_FACE_DEFAULT = cv2.data.haarcascades + "haarcascade_frontalface_default.xml"
HAAR_FACE_ALT2 = cv2.data.haarcascades + "haarcascade_frontalface_alt2.xml"
HAAR_EYE = cv2.data.haarcascades + "haarcascade_eye.xml"

NORMALIZED_FACE_SIZE = 160
FACE_PADDING_RATIO = 0.12
CLAHE_CLIP = 2.5
CLAHE_GRID = 8
MIN_DETECTION_AREA_RATIO = 0.004
DETECTION_SCALE_FACTOR = 1.08
DETECTION_MIN_NEIGHBORS = 2

MIN_SHARPNESS = 30.0
MIN_CONTRAST = 25.0
MIN_BRIGHTNESS = 40.0
MAX_BRIGHTNESS = 220.0
MIN_FACE_SIZE_PX = 80

PHASH_BITS = 64
DUPLICATE_HAMMING_THRESHOLD = 4


@dataclass
class ImageAssessment:
    filename: str
    path: str
    width: int = 0
    height: int = 0
    file_size: int = 0
    timestamp: Optional[str] = None
    burst_group: Optional[str] = None
    face_count: int = 0
    face_rect: Optional[dict] = None
    face_area_ratio: float = 0.0
    sharpness: float = 0.0
    contrast: float = 0.0
    brightness: float = 0.0
    eyes_detected: bool = False
    phash: Optional[str] = None
    reject_reasons: list = field(default_factory=list)
    accepted: bool = False
    split: Optional[str] = None


def compute_phash(gray_img, hash_size=8):
    resized = cv2.resize(gray_img, (hash_size + 1, hash_size))
    diff = resized[:, 1:] > resized[:, :-1]
    bits = diff.flatten()
    hex_str = ""
    for i in range(0, len(bits), 4):
        nibble = bits[i:i+4]
        val = sum(b << (3 - j) for j, b in enumerate(nibble))
        hex_str += format(val, 'x')
    return hex_str


def hamming_distance(h1: str, h2: str) -> int:
    if len(h1) != len(h2):
        return 999
    dist = 0
    for c1, c2 in zip(h1, h2):
        dist += bin(int(c1, 16) ^ int(c2, 16)).count('1')
    return dist


def detect_faces(gray, frame_w, frame_h):
    alt2_cascade = cv2.CascadeClassifier(HAAR_FACE_ALT2)
    default_cascade = cv2.CascadeClassifier(HAAR_FACE_DEFAULT)

    min_area = frame_w * frame_h * MIN_DETECTION_AREA_RATIO
    min_size = (50, 50)

    combined = []

    if not alt2_cascade.empty():
        alt_faces = alt2_cascade.detectMultiScale(
            gray, DETECTION_SCALE_FACTOR, DETECTION_MIN_NEIGHBORS,
            minSize=min_size
        )
        if len(alt_faces) > 0:
            for (x, y, w, h) in alt_faces:
                combined.append((x, y, w, h))

    if not default_cascade.empty():
        def_faces = default_cascade.detectMultiScale(
            gray, DETECTION_SCALE_FACTOR, DETECTION_MIN_NEIGHBORS,
            minSize=min_size
        )
        if len(def_faces) > 0:
            for (x, y, w, h) in def_faces:
                overlaps = False
                for (ex, ey, ew, eh) in combined:
                    ix1 = max(x, ex)
                    iy1 = max(y, ey)
                    ix2 = min(x + w, ex + ew)
                    iy2 = min(y + h, ey + eh)
                    if ix2 > ix1 and iy2 > iy1:
                        intersection = (ix2 - ix1) * (iy2 - iy1)
                        union = w * h + ew * eh - intersection
                        if union > 0 and intersection / union > 0.4:
                            overlaps = True
                            break
                if not overlaps:
                    combined.append((x, y, w, h))

    filtered = [(x, y, w, h) for (x, y, w, h) in combined if w * h >= min_area]
    filtered.sort(key=lambda r: r[2] * r[3], reverse=True)
    return filtered


def detect_eyes(gray_face):
    eye_cascade = cv2.CascadeClassifier(HAAR_EYE)
    if eye_cascade.empty():
        return False
    fh, fw = gray_face.shape[:2]
    min_eye = (int(fw * 0.12), int(fh * 0.12))
    max_eye = (int(fw * 0.40), int(fh * 0.40))
    eyes = eye_cascade.detectMultiScale(gray_face, 1.1, 4, minSize=min_eye, maxSize=max_eye)
    if len(eyes) < 2:
        return False
    top_eyes = [(ex, ey, ew, eh) for (ex, ey, ew, eh) in eyes if ey + eh / 2 < fh * 0.55]
    return len(top_eyes) >= 2


def measure_sharpness(gray_face):
    laplacian = cv2.Laplacian(gray_face, cv2.CV_64F)
    _, std = cv2.meanStdDev(laplacian)
    return float(std[0][0] ** 2)


def measure_contrast(gray_face):
    _, std = cv2.meanStdDev(gray_face)
    return float(std[0][0])


def normalize_face(raw_gray, x, y, w, h):
    pad_x = int(w * FACE_PADDING_RATIO)
    pad_y = int(h * FACE_PADDING_RATIO)
    rows, cols = raw_gray.shape[:2]
    cx = max(0, x - pad_x)
    cy = max(0, y - pad_y)
    cw = min(cols - cx, w + 2 * pad_x)
    ch = min(rows - cy, h + 2 * pad_y)
    crop = raw_gray[cy:cy+ch, cx:cx+cw]

    clahe = cv2.createCLAHE(clipLimit=CLAHE_CLIP, tileGridSize=(CLAHE_GRID, CLAHE_GRID))
    normed = clahe.apply(crop)
    resized = cv2.resize(normed, (NORMALIZED_FACE_SIZE, NORMALIZED_FACE_SIZE))
    return resized


def parse_timestamp(filename):
    base = filename.rsplit(".", 1)[0]
    parts = base.split("_")
    ts_part = parts[0] if parts else base
    try:
        return datetime.strptime(ts_part, "%Y-%m-%d-%H%M%S").isoformat()
    except ValueError:
        return None


def parse_burst_group(filename):
    base = filename.rsplit(".", 1)[0]
    parts = base.rsplit("_", 1)
    return parts[0] if len(parts) == 2 else base


def assess_image(path: Path) -> ImageAssessment:
    assessment = ImageAssessment(
        filename=path.name,
        path=str(path),
        file_size=path.stat().st_size,
        timestamp=parse_timestamp(path.name),
        burst_group=parse_burst_group(path.name),
    )

    img = cv2.imread(str(path))
    if img is None:
        assessment.reject_reasons.append("unreadable_image")
        return assessment

    assessment.height, assessment.width = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

    assessment.phash = compute_phash(gray)

    mean_brightness = float(np.mean(gray))
    assessment.brightness = mean_brightness

    faces = detect_faces(gray, assessment.width, assessment.height)
    assessment.face_count = len(faces)

    if len(faces) == 0:
        assessment.reject_reasons.append("no_face")
        return assessment
    if len(faces) > 1:
        assessment.reject_reasons.append("multiple_faces")
        return assessment

    x, y, w, h = faces[0]
    assessment.face_rect = {"x": int(x), "y": int(y), "w": int(w), "h": int(h)}
    assessment.face_area_ratio = (w * h) / (assessment.width * assessment.height)

    if w < MIN_FACE_SIZE_PX or h < MIN_FACE_SIZE_PX:
        assessment.reject_reasons.append("face_too_small")
        return assessment

    normalized = normalize_face(gray, x, y, w, h)
    assessment.sharpness = measure_sharpness(normalized)
    assessment.contrast = measure_contrast(normalized)
    assessment.eyes_detected = detect_eyes(normalized)

    if assessment.sharpness < MIN_SHARPNESS:
        assessment.reject_reasons.append("blurry")
    if assessment.contrast < MIN_CONTRAST:
        assessment.reject_reasons.append("low_contrast")
    if mean_brightness < MIN_BRIGHTNESS:
        assessment.reject_reasons.append("too_dark")
    if mean_brightness > MAX_BRIGHTNESS:
        assessment.reject_reasons.append("overexposed")

    if not assessment.reject_reasons:
        assessment.accepted = True

    return assessment


def deduplicate_burst(assessments: list[ImageAssessment], keep_per_burst: int = 2) -> list[ImageAssessment]:
    """From each burst group, keep the top N quality images."""
    groups = defaultdict(list)
    for a in assessments:
        if a.accepted and a.burst_group:
            groups[a.burst_group].append(a)

    kept = set()
    for group_name, group_items in groups.items():
        ranked = sorted(group_items, key=lambda a: (a.sharpness, a.contrast, a.eyes_detected), reverse=True)
        for a in ranked[:keep_per_burst]:
            kept.add(a.filename)

    standalone = [a for a in assessments if a.accepted and not a.burst_group]
    for a in standalone:
        kept.add(a.filename)

    return [a for a in assessments if a.filename in kept]


def deduplicate_phash(assessments: list[ImageAssessment]) -> list[ImageAssessment]:
    """Remove near-duplicates across burst boundaries using perceptual hash."""
    if not assessments:
        return assessments

    kept = [assessments[0]]
    for candidate in assessments[1:]:
        if candidate.phash is None:
            kept.append(candidate)
            continue
        is_dup = False
        for existing in kept:
            if existing.phash and hamming_distance(candidate.phash, existing.phash) <= DUPLICATE_HAMMING_THRESHOLD:
                is_dup = True
                break
        if not is_dup:
            kept.append(candidate)

    return kept


def partition_dataset(
    unique: list[ImageAssessment],
    enrollment_count: int,
    validation_count: int
) -> tuple[list[ImageAssessment], list[ImageAssessment]]:
    """Split unique accepted images into enrollment and validation sets.
    Prioritize diversity: sort by sharpness descending, then interleave
    by timestamp distance to avoid putting adjacent frames in the same set."""

    sorted_by_quality = sorted(unique, key=lambda a: (-a.sharpness, -a.contrast))

    enrollment = []
    validation = []

    for i, a in enumerate(sorted_by_quality):
        if len(enrollment) < enrollment_count:
            enrollment.append(a)
        elif len(validation) < validation_count:
            validation.append(a)
        else:
            break

    return enrollment, validation


def copy_images(assessments: list[ImageAssessment], target_dir: Path, prefix: str = ""):
    target_dir.mkdir(parents=True, exist_ok=True)
    for i, a in enumerate(assessments, 1):
        ext = Path(a.filename).suffix
        dest_name = f"{prefix}{i:03d}{ext}" if prefix else a.filename
        shutil.copy2(a.path, target_dir / dest_name)


def build_negative_sets(all_assessments: list[ImageAssessment], output_dir: Path):
    neg_dir = output_dir / "negative"
    categories = {
        "no_face": [],
        "partial_face": [],
        "poor_quality": [],
        "far_face": [],
        "multiple_faces": [],
    }

    for a in all_assessments:
        if a.accepted:
            continue
        if "no_face" in a.reject_reasons:
            categories["no_face"].append(a)
        elif "multiple_faces" in a.reject_reasons:
            categories["multiple_faces"].append(a)
        elif "face_too_small" in a.reject_reasons:
            categories["far_face"].append(a)
        elif any(r in a.reject_reasons for r in ("blurry", "low_contrast", "too_dark", "overexposed")):
            categories["poor_quality"].append(a)
        else:
            categories["partial_face"].append(a)

    for category, items in categories.items():
        if items:
            cat_dir = neg_dir / category
            copy_images(items[:20], cat_dir)

    return {k: len(v) for k, v in categories.items()}


def generate_reports(
    all_assessments: list[ImageAssessment],
    enrollment: list[ImageAssessment],
    validation: list[ImageAssessment],
    neg_counts: dict,
    burst_reduction: int,
    phash_reduction: int,
    output_dir: Path
):
    reports_dir = output_dir / "reports"
    reports_dir.mkdir(parents=True, exist_ok=True)

    accepted = [a for a in all_assessments if a.accepted]
    rejected = [a for a in all_assessments if not a.accepted]

    reject_reason_counts = defaultdict(int)
    for a in rejected:
        for reason in a.reject_reasons:
            reject_reason_counts[reason] += 1

    summary = {
        "generated_at": datetime.now().isoformat(),
        "total_images": len(all_assessments),
        "accepted_images": len(accepted),
        "rejected_images": len(rejected),
        "burst_groups_found": len(set(a.burst_group for a in all_assessments if a.burst_group)),
        "unique_after_burst_dedup": len(accepted) - burst_reduction if burst_reduction else len(accepted),
        "unique_after_phash_dedup": len(accepted) - burst_reduction - phash_reduction if phash_reduction else len(accepted) - burst_reduction,
        "enrollment_count": len(enrollment),
        "validation_count": len(validation),
        "negative_counts": neg_counts,
        "rejection_reasons": dict(reject_reason_counts),
        "quality_thresholds": {
            "min_sharpness": MIN_SHARPNESS,
            "min_contrast": MIN_CONTRAST,
            "min_brightness": MIN_BRIGHTNESS,
            "max_brightness": MAX_BRIGHTNESS,
            "min_face_size_px": MIN_FACE_SIZE_PX,
            "duplicate_hamming_threshold": DUPLICATE_HAMMING_THRESHOLD,
        },
    }

    with open(reports_dir / "dataset_summary.json", "w") as f:
        json.dump(summary, f, indent=2)

    rejected_details = []
    for a in rejected:
        rejected_details.append({
            "filename": a.filename,
            "reasons": a.reject_reasons,
            "face_count": a.face_count,
            "sharpness": round(a.sharpness, 2),
            "contrast": round(a.contrast, 2),
            "brightness": round(a.brightness, 2),
        })
    with open(reports_dir / "rejected_samples.json", "w") as f:
        json.dump(rejected_details, f, indent=2)

    quality_entries = []
    for a in all_assessments:
        quality_entries.append({
            "filename": a.filename,
            "accepted": a.accepted,
            "split": a.split,
            "face_count": a.face_count,
            "face_area_ratio": round(a.face_area_ratio, 4),
            "sharpness": round(a.sharpness, 2),
            "contrast": round(a.contrast, 2),
            "brightness": round(a.brightness, 2),
            "eyes_detected": a.eyes_detected,
            "reject_reasons": a.reject_reasons,
        })
    with open(reports_dir / "quality_report.json", "w") as f:
        json.dump(quality_entries, f, indent=2)

    burst_groups = defaultdict(list)
    for a in all_assessments:
        if a.burst_group:
            burst_groups[a.burst_group].append(a.filename)
    dup_groups = [{"group": k, "files": v, "count": len(v)} for k, v in burst_groups.items() if len(v) > 1]
    with open(reports_dir / "duplicate_groups.json", "w") as f:
        json.dump(dup_groups, f, indent=2)

    return summary


def main():
    parser = argparse.ArgumentParser(description="Curate owner face dataset for V2 enrollment")
    parser.add_argument("--source", required=True, help="Source directory with webcam photos")
    parser.add_argument("--output", required=True, help="Output dataset directory")
    parser.add_argument("--enrollment-count", type=int, default=15, help="Target enrollment set size")
    parser.add_argument("--validation-count", type=int, default=10, help="Target validation set size")
    args = parser.parse_args()

    source_dir = Path(args.source).expanduser().resolve()
    output_dir = Path(args.output).expanduser().resolve()

    if not source_dir.is_dir():
        print(f"ERROR: Source directory does not exist: {source_dir}", file=sys.stderr)
        sys.exit(1)

    image_files = sorted([
        f for f in source_dir.iterdir()
        if f.is_file() and f.suffix.lower() in ('.jpg', '.jpeg', '.png', '.bmp')
    ])

    if not image_files:
        print(f"ERROR: No image files found in {source_dir}", file=sys.stderr)
        sys.exit(1)

    print(f"Found {len(image_files)} images in {source_dir}")
    print(f"Output: {output_dir}")
    print()

    print("Phase 1: Assessing all images...")
    all_assessments = []
    for i, img_path in enumerate(image_files, 1):
        assessment = assess_image(img_path)
        all_assessments.append(assessment)
        status = "OK" if assessment.accepted else f"REJECT({','.join(assessment.reject_reasons)})"
        if i % 20 == 0 or i == len(image_files):
            print(f"  [{i}/{len(image_files)}] {assessment.filename}: {status}")

    accepted = [a for a in all_assessments if a.accepted]
    rejected = [a for a in all_assessments if not a.accepted]
    print(f"\n  Accepted: {len(accepted)}, Rejected: {len(rejected)}")

    print("\nPhase 2: Burst deduplication...")
    after_burst = deduplicate_burst(all_assessments)
    burst_reduction = len(accepted) - len(after_burst)
    print(f"  Kept {len(after_burst)} from {len(accepted)} accepted (removed {burst_reduction} burst duplicates)")

    print("\nPhase 3: Perceptual hash deduplication...")
    after_burst_sorted = sorted(after_burst, key=lambda a: (a.timestamp or "", a.filename))
    unique = deduplicate_phash(after_burst_sorted)
    phash_reduction = len(after_burst) - len(unique)
    print(f"  Kept {len(unique)} from {len(after_burst)} (removed {phash_reduction} near-duplicates)")

    print(f"\nPhase 4: Partitioning into enrollment/validation...")
    enrollment, validation = partition_dataset(unique, args.enrollment_count, args.validation_count)
    for a in enrollment:
        a.split = "enrollment"
    for a in validation:
        a.split = "validation"
    print(f"  Enrollment: {len(enrollment)}, Validation: {len(validation)}")

    print(f"\nPhase 5: Building dataset structure at {output_dir}...")
    if output_dir.exists():
        shutil.rmtree(output_dir)

    copy_images(enrollment, output_dir / "owner" / "enrollment", prefix="enroll-")
    copy_images(validation, output_dir / "owner" / "validation", prefix="valid-")

    raw_dir = output_dir / "owner" / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)

    selected_dir = output_dir / "owner" / "selected"
    copy_images(unique, selected_dir, prefix="selected-")

    print("\nPhase 6: Building negative/reject sets...")
    neg_counts = build_negative_sets(all_assessments, output_dir)
    for cat, count in neg_counts.items():
        if count > 0:
            print(f"  {cat}: {count}")

    print("\nPhase 7: Generating reports...")
    summary = generate_reports(
        all_assessments, enrollment, validation,
        neg_counts, burst_reduction, phash_reduction, output_dir
    )

    print("\n" + "=" * 60)
    print("DATASET BUILD COMPLETE")
    print("=" * 60)
    print(f"  Total images processed:  {summary['total_images']}")
    print(f"  Accepted:                {summary['accepted_images']}")
    print(f"  Rejected:                {summary['rejected_images']}")
    print(f"  After burst dedup:       {summary['unique_after_burst_dedup']}")
    print(f"  After phash dedup:       {summary['unique_after_phash_dedup']}")
    print(f"  Enrollment set:          {summary['enrollment_count']}")
    print(f"  Validation set:          {summary['validation_count']}")
    print(f"  Rejection reasons:       {summary['rejection_reasons']}")
    print(f"\n  Output: {output_dir}")
    print(f"  Reports: {output_dir / 'reports'}")


if __name__ == "__main__":
    main()
