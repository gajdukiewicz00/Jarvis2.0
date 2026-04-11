#!/usr/bin/env python3
"""
Vision Security V2 — Dataset Validation / Sanity Check

Validates an existing curated dataset by checking:
  - File counts per split
  - Face detection success rate
  - Quality statistics
  - Split integrity (no duplicates between enrollment and validation)
  - Perceptual hash uniqueness

Usage:
  python3 scripts/validate-owner-dataset.py \
      --dataset apps/vision-security-service/dataset
"""

import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np


HAAR_FACE = cv2.data.haarcascades + "haarcascade_frontalface_default.xml"
HAAR_FACE_ALT2 = cv2.data.haarcascades + "haarcascade_frontalface_alt2.xml"


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


def hamming_distance(h1, h2):
    if len(h1) != len(h2):
        return 999
    return sum(bin(int(c1, 16) ^ int(c2, 16)).count('1') for c1, c2 in zip(h1, h2))


def detect_face_count(gray, w, h):
    cascade = cv2.CascadeClassifier(HAAR_FACE_ALT2)
    if cascade.empty():
        cascade = cv2.CascadeClassifier(HAAR_FACE)
    faces = cascade.detectMultiScale(gray, 1.08, 2, minSize=(50, 50))
    return len(faces)


def measure_quality(gray):
    laplacian = cv2.Laplacian(gray, cv2.CV_64F)
    _, std_lap = cv2.meanStdDev(laplacian)
    sharpness = float(std_lap[0][0] ** 2)
    _, std_img = cv2.meanStdDev(gray)
    contrast = float(std_img[0][0])
    brightness = float(np.mean(gray))
    return sharpness, contrast, brightness


def validate_split(name, directory):
    if not directory.exists():
        return {"status": "MISSING", "count": 0}

    files = sorted([f for f in directory.iterdir() if f.is_file() and f.suffix.lower() in ('.jpg', '.jpeg', '.png')])
    results = {
        "status": "OK",
        "count": len(files),
        "face_detected": 0,
        "no_face": 0,
        "multi_face": 0,
        "sharpness_min": float('inf'),
        "sharpness_max": 0,
        "sharpness_mean": 0,
        "contrast_min": float('inf'),
        "contrast_max": 0,
        "contrast_mean": 0,
        "hashes": [],
    }

    sharpness_sum = 0
    contrast_sum = 0

    for f in files:
        img = cv2.imread(str(f))
        if img is None:
            results["no_face"] += 1
            continue
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        h, w = gray.shape[:2]

        fc = detect_face_count(gray, w, h)
        if fc == 1:
            results["face_detected"] += 1
        elif fc == 0:
            results["no_face"] += 1
        else:
            results["multi_face"] += 1

        sharpness, contrast, brightness = measure_quality(gray)
        results["sharpness_min"] = min(results["sharpness_min"], sharpness)
        results["sharpness_max"] = max(results["sharpness_max"], sharpness)
        sharpness_sum += sharpness
        results["contrast_min"] = min(results["contrast_min"], contrast)
        results["contrast_max"] = max(results["contrast_max"], contrast)
        contrast_sum += contrast

        results["hashes"].append(compute_phash(gray))

    if files:
        results["sharpness_mean"] = round(sharpness_sum / len(files), 2)
        results["contrast_mean"] = round(contrast_sum / len(files), 2)
    else:
        results["sharpness_min"] = 0
        results["contrast_min"] = 0

    results["sharpness_min"] = round(results["sharpness_min"], 2)
    results["sharpness_max"] = round(results["sharpness_max"], 2)
    results["contrast_min"] = round(results["contrast_min"], 2)
    results["contrast_max"] = round(results["contrast_max"], 2)

    return results


def main():
    parser = argparse.ArgumentParser(description="Validate curated owner face dataset")
    parser.add_argument("--dataset", required=True, help="Path to dataset directory")
    args = parser.parse_args()

    dataset = Path(args.dataset).expanduser().resolve()
    if not dataset.is_dir():
        print(f"ERROR: Dataset directory does not exist: {dataset}", file=sys.stderr)
        sys.exit(1)

    print(f"Validating dataset at: {dataset}\n")

    enrollment_dir = dataset / "owner" / "enrollment"
    validation_dir = dataset / "owner" / "validation"
    selected_dir = dataset / "owner" / "selected"

    print("--- ENROLLMENT ---")
    enr = validate_split("enrollment", enrollment_dir)
    print(f"  Count:          {enr['count']}")
    print(f"  Face detected:  {enr['face_detected']}")
    print(f"  No face:        {enr['no_face']}")
    print(f"  Multi face:     {enr['multi_face']}")
    print(f"  Sharpness:      min={enr['sharpness_min']}, max={enr['sharpness_max']}, mean={enr['sharpness_mean']}")
    print(f"  Contrast:       min={enr['contrast_min']}, max={enr['contrast_max']}, mean={enr['contrast_mean']}")

    print("\n--- VALIDATION ---")
    val = validate_split("validation", validation_dir)
    print(f"  Count:          {val['count']}")
    print(f"  Face detected:  {val['face_detected']}")
    print(f"  No face:        {val['no_face']}")
    print(f"  Multi face:     {val['multi_face']}")
    print(f"  Sharpness:      min={val['sharpness_min']}, max={val['sharpness_max']}, mean={val['sharpness_mean']}")
    print(f"  Contrast:       min={val['contrast_min']}, max={val['contrast_max']}, mean={val['contrast_mean']}")

    print("\n--- SPLIT INTEGRITY ---")
    cross_duplicates = 0
    for h_enr in enr.get("hashes", []):
        for h_val in val.get("hashes", []):
            if hamming_distance(h_enr, h_val) <= 3:
                cross_duplicates += 1
    print(f"  Cross-split near-duplicates (hamming<=3): {cross_duplicates}")
    if cross_duplicates == 0:
        print("  PASS: enrollment and validation sets are distinct")
    else:
        print(f"  WARN: {cross_duplicates} near-duplicate pair(s) found across splits")

    neg_dir = dataset / "negative"
    print("\n--- NEGATIVE SETS ---")
    if neg_dir.is_dir():
        for cat_dir in sorted(neg_dir.iterdir()):
            if cat_dir.is_dir():
                count = len(list(cat_dir.iterdir()))
                print(f"  {cat_dir.name}: {count}")
    else:
        print("  No negative directory found")

    reports_dir = dataset / "reports"
    print("\n--- REPORTS ---")
    if reports_dir.is_dir():
        for report in sorted(reports_dir.iterdir()):
            if report.suffix == ".json":
                size = report.stat().st_size
                print(f"  {report.name}: {size} bytes")
    else:
        print("  No reports directory found")

    summary_path = reports_dir / "dataset_summary.json"
    if summary_path.exists():
        with open(summary_path) as f:
            summary = json.load(f)
        print(f"\n--- SUMMARY (from report) ---")
        print(f"  Total images:     {summary.get('total_images')}")
        print(f"  Accepted:         {summary.get('accepted_images')}")
        print(f"  Rejected:         {summary.get('rejected_images')}")
        print(f"  Enrollment:       {summary.get('enrollment_count')}")
        print(f"  Validation:       {summary.get('validation_count')}")

    print("\n--- VERDICT ---")
    issues = []
    if enr["count"] < 5:
        issues.append(f"Enrollment set too small ({enr['count']} < 5)")
    if val["count"] < 3:
        issues.append(f"Validation set too small ({val['count']} < 3)")
    if enr["no_face"] > 0:
        issues.append(f"Enrollment has {enr['no_face']} images without detected face")
    if cross_duplicates > 0:
        issues.append(f"{cross_duplicates} cross-split near-duplicates")
    face_rate = enr["face_detected"] / max(1, enr["count"]) * 100
    if face_rate < 80:
        issues.append(f"Enrollment face detection rate is low ({face_rate:.0f}%)")

    if not issues:
        print("  PASS: Dataset looks healthy for V2 enrollment")
    else:
        for issue in issues:
            print(f"  ISSUE: {issue}")

    sys.exit(0 if not issues else 1)


if __name__ == "__main__":
    main()
