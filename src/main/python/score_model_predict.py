#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import csv
import json
import math
import os
import sys
from pathlib import Path

import joblib
import numpy as np
from PIL import Image


def find_project_root():
    for parent in [Path.cwd(), *Path(__file__).resolve().parents]:
        if (parent / "score_model.joblib").exists() and (parent / "nail_styles.csv").exists():
            return parent
    raise FileNotFoundError("score_model.joblib and nail_styles.csv were not found in project root")


ROOT = find_project_root()
MODEL_PATH = ROOT / "score_model.joblib"
STYLE_CSV = ROOT / "nail_styles.csv"

LEGACY_FEATURE_COLS = [
    "img_width",
    "img_height",
    "aspect_ratio",
    "brightness_mean",
    "brightness_std",
    "r_mean",
    "g_mean",
    "b_mean",
    "skin_mask_ratio",
    "center_brightness",
    "style_length",
    "style_color_warmth",
    "style_complexity",
    "style_gloss",
    "style_edge_roundness",
]

MEDIAPIPE_FEATURE_COLS = [
    "mediapipe_detected",
    "hand_detection_confidence",
    "hand_bbox_width",
    "hand_bbox_height",
    "hand_bbox_area",
    "hand_bbox_aspect",
    "palm_width_ratio",
    "palm_length_ratio",
    "finger_length_ratio",
    "finger_spread_ratio",
    "thumb_openness_ratio",
    "landmark_z_range",
    "fingertip_brightness",
    "fingertip_redness",
]

DEFAULT_FEATURE_COLS = LEGACY_FEATURE_COLS + MEDIAPIPE_FEATURE_COLS
_MEDIAPIPE_MODULE = None
_MEDIAPIPE_IMPORT_FAILED = False
HAND_LANDMARKER_FILENAME = "hand_landmarker.task"


def load_payload():
    return json.loads(sys.stdin.read() or "{}")


def clamp(value, low=0.0, high=1.0):
    return max(low, min(high, float(value)))


def point_distance(a, b):
    return float(math.dist(a[:2], b[:2]))


def default_mediapipe_features():
    return {
        "mediapipe_detected": 0.0,
        "hand_detection_confidence": 0.0,
        "hand_bbox_width": 0.0,
        "hand_bbox_height": 0.0,
        "hand_bbox_area": 0.0,
        "hand_bbox_aspect": 0.0,
        "palm_width_ratio": 0.0,
        "palm_length_ratio": 0.0,
        "finger_length_ratio": 0.0,
        "finger_spread_ratio": 0.0,
        "thumb_openness_ratio": 0.0,
        "landmark_z_range": 0.0,
        "fingertip_brightness": 0.0,
        "fingertip_redness": 0.0,
    }


def load_mediapipe():
    global _MEDIAPIPE_MODULE, _MEDIAPIPE_IMPORT_FAILED
    if _MEDIAPIPE_IMPORT_FAILED:
        return None
    if _MEDIAPIPE_MODULE is None:
        try:
            import mediapipe as mp
            from mediapipe.tasks import python as mp_python
            from mediapipe.tasks.python import vision

            _MEDIAPIPE_MODULE = (mp, mp_python, vision)
        except Exception:
            _MEDIAPIPE_IMPORT_FAILED = True
            return None
    return _MEDIAPIPE_MODULE


def hand_landmarker_model_path():
    env_path = os.environ.get("MEDIAPIPE_HAND_LANDMARKER_MODEL", "").strip()
    candidates = []
    if env_path:
        candidates.append(Path(env_path).expanduser())
    for parent in [ROOT, *Path(__file__).resolve().parents]:
        candidates.append(parent / "models" / "mediapipe" / HAND_LANDMARKER_FILENAME)
    for candidate in candidates:
        if candidate.exists():
            return candidate.resolve()
    return None


def sample_patch(arr, point, radius=5):
    h, w = arr.shape[:2]
    x = int(round(clamp(point[0]) * (w - 1)))
    y = int(round(clamp(point[1]) * (h - 1)))
    x0 = max(0, x - radius)
    x1 = min(w, x + radius + 1)
    y0 = max(0, y - radius)
    y1 = min(h, y + radius + 1)
    return arr[y0:y1, x0:x1]


def rotated_points_to_original(points, rotation):
    if rotation == 0:
        return points
    mapped = points.copy()
    x = points[:, 0].copy()
    y = points[:, 1].copy()
    if rotation == 1:
        mapped[:, 0] = y
        mapped[:, 1] = 1.0 - x
    elif rotation == 2:
        mapped[:, 0] = 1.0 - x
        mapped[:, 1] = 1.0 - y
    elif rotation == 3:
        mapped[:, 0] = 1.0 - y
        mapped[:, 1] = x
    mapped[:, :2] = np.clip(mapped[:, :2], 0.0, 1.0)
    return mapped


def detect_hand_landmarks(arr, mp, mp_python, vision, model_path):
    options = vision.HandLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=str(model_path)),
        running_mode=vision.RunningMode.IMAGE,
        num_hands=1,
        min_hand_detection_confidence=0.10,
        min_hand_presence_confidence=0.10,
        min_tracking_confidence=0.10,
    )
    with vision.HandLandmarker.create_from_options(options) as landmarker:
        for rotation in [0, 1, 2, 3]:
            candidate = arr if rotation == 0 else np.ascontiguousarray(np.rot90(arr, rotation))
            image = mp.Image(image_format=mp.ImageFormat.SRGB, data=candidate)
            result = landmarker.detect(image)
            if result.hand_landmarks:
                landmarks = result.hand_landmarks[0]
                points = np.array([[lm.x, lm.y, lm.z] for lm in landmarks], dtype=np.float32)
                return rotated_points_to_original(points, rotation), result.handedness
    return None, None


def mediapipe_hand_features(arr, enabled=True):
    features = default_mediapipe_features()
    if not enabled:
        return features

    modules = load_mediapipe()
    model_path = hand_landmarker_model_path()
    if modules is None or model_path is None:
        return features
    mp, mp_python, vision = modules

    try:
        points, handedness = detect_hand_landmarks(arr, mp, mp_python, vision, model_path)
    except Exception:
        return features

    if points is None:
        return features

    xy = np.clip(points[:, :2], 0.0, 1.0)
    x_min, y_min = xy.min(axis=0)
    x_max, y_max = xy.max(axis=0)
    bbox_width = max(0.0, float(x_max - x_min))
    bbox_height = max(0.0, float(y_max - y_min))
    palm_width = point_distance(xy[5], xy[17])
    palm_length = point_distance(xy[0], xy[9])
    finger_lengths = [
        point_distance(xy[5], xy[8]),
        point_distance(xy[9], xy[12]),
        point_distance(xy[13], xy[16]),
        point_distance(xy[17], xy[20]),
    ]
    finger_spread = point_distance(xy[8], xy[20])
    thumb_openness = point_distance(xy[4], xy[9])

    confidence = 1.0
    if handedness:
        classifications = handedness[0]
        if classifications:
            confidence = float(classifications[0].score)

    fingertip_points = [xy[i] for i in [4, 8, 12, 16, 20]]
    fingertip_patches = [sample_patch(arr, point) for point in fingertip_points]
    fingertip_pixels = np.concatenate([patch.reshape(-1, 3) for patch in fingertip_patches if patch.size], axis=0)
    if fingertip_pixels.size:
        fingertip_rgb = fingertip_pixels.astype(np.float32)
        fingertip_brightness = float(fingertip_rgb.mean(axis=1).mean())
        fingertip_redness = float((fingertip_rgb[:, 0] - (fingertip_rgb[:, 1] + fingertip_rgb[:, 2]) / 2).mean())
    else:
        fingertip_brightness = 0.0
        fingertip_redness = 0.0

    eps = 1e-6
    features.update({
        "mediapipe_detected": 1.0,
        "hand_detection_confidence": clamp(confidence),
        "hand_bbox_width": bbox_width,
        "hand_bbox_height": bbox_height,
        "hand_bbox_area": bbox_width * bbox_height,
        "hand_bbox_aspect": bbox_width / max(bbox_height, eps),
        "palm_width_ratio": palm_width,
        "palm_length_ratio": palm_length,
        "finger_length_ratio": float(np.mean(finger_lengths) / max(palm_length, eps)),
        "finger_spread_ratio": finger_spread / max(palm_width, eps),
        "thumb_openness_ratio": thumb_openness / max(palm_width, eps),
        "landmark_z_range": float(points[:, 2].max() - points[:, 2].min()),
        "fingertip_brightness": fingertip_brightness,
        "fingertip_redness": fingertip_redness,
    })
    return features


def image_features(image_path, use_mediapipe=True):
    img = Image.open(image_path).convert("RGB")
    arr_u8 = np.asarray(img).astype(np.uint8)
    arr = arr_u8.astype(np.float32)
    gray = arr.mean(axis=2)
    h, w = arr.shape[:2]
    center = gray[h // 4: h * 3 // 4, w // 4: w * 3 // 4]
    r, g, b = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2]
    skin_mask = (r > 95) & (g > 40) & (b > 20) & ((r - g) > 8) & (r > b)
    return {
        "img_width": float(w),
        "img_height": float(h),
        "aspect_ratio": float(w / h) if h else 1.0,
        "brightness_mean": float(gray.mean()),
        "brightness_std": float(gray.std()),
        "r_mean": float(r.mean()),
        "g_mean": float(g.mean()),
        "b_mean": float(b.mean()),
        "skin_mask_ratio": float(skin_mask.mean()),
        "center_brightness": float(center.mean()) if center.size else float(gray.mean()),
        **mediapipe_hand_features(arr_u8, enabled=use_mediapipe),
    }


def load_style_features(style_code):
    with STYLE_CSV.open("r", encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            if row["style_id"] == style_code:
                return {
                    "style_length": float(row["style_length"]),
                    "style_color_warmth": float(row["style_color_warmth"]),
                    "style_complexity": float(row["style_complexity"]),
                    "style_gloss": float(row["style_gloss"]),
                    "style_edge_roundness": float(row["style_edge_roundness"]),
                    "style_name": row["style_name"],
                }
    raise ValueError(f"style_id not found in nail_styles.csv: {style_code}")


def p5p95_normalize(features, stats, cols):
    normalized = {}
    for col in cols:
        value = float(features[col])
        item = stats.get(col, {})
        p5 = float(item.get("p5", 0.0))
        p95 = float(item.get("p95", 1.0))
        if abs(p95 - p5) < 1e-9:
            normalized[col] = 0.5
        else:
            normalized[col] = min(1.0, max(0.0, (value - p5) / (p95 - p5)))
    return normalized


def bool_payload(value, default=True):
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() not in {"0", "false", "no", "off", "否"}


def model_uses_mediapipe(feature_cols):
    return any(col in MEDIAPIPE_FEATURE_COLS for col in feature_cols)


def mediapipe_fit_score(raw_features, style):
    if float(raw_features.get("mediapipe_detected", 0.0)) < 0.5:
        return None

    eps = 1e-6
    style_length = clamp(style.get("style_length", 0.4))
    style_roundness = clamp(style.get("style_edge_roundness", 0.7))
    palm_shape = raw_features["palm_length_ratio"] / max(raw_features["palm_width_ratio"], eps)
    slenderness = clamp((palm_shape - 0.85) / 0.9)
    finger_profile = clamp((raw_features["finger_length_ratio"] - 0.45) / 0.55)
    hand_profile = slenderness * 0.55 + finger_profile * 0.45
    target_length = 0.30 + hand_profile * 0.35
    target_roundness = 0.84 - hand_profile * 0.22
    length_match = 1.0 - min(1.0, abs(style_length - target_length) / 0.55)
    roundness_match = 1.0 - min(1.0, abs(style_roundness - target_roundness) / 0.60)
    spread_balance = 1.0 - min(1.0, abs(raw_features["finger_spread_ratio"] - 1.70) / 1.70)
    brightness = clamp((raw_features["fingertip_brightness"] - 80.0) / 120.0)
    confidence = clamp(raw_features.get("hand_detection_confidence", 0.85))
    score = 55 + length_match * 20 + roundness_match * 10 + spread_balance * 7 + brightness * 5 + confidence * 3
    return max(0.0, min(100.0, score))


def blend_score_with_mediapipe(model_score, raw_features, style, feature_cols):
    hand_score = mediapipe_fit_score(raw_features, style)
    if hand_score is None:
        return model_score, None
    if model_uses_mediapipe(feature_cols):
        return model_score, hand_score
    return model_score * 0.78 + hand_score * 0.22, hand_score


def main():
    payload = load_payload()
    image_path = payload["imagePath"]
    style_code = payload.get("styleCode") or "nail_01"
    use_mediapipe = bool_payload(payload.get("useMediapipe"), True)
    model_path = Path(payload.get("modelPath") or MODEL_PATH).expanduser()
    if not model_path.is_absolute():
        model_path = (ROOT / model_path).resolve()
    bundle = joblib.load(model_path)
    model = bundle["model"]
    feature_cols = bundle["feature_cols"]
    stats = bundle.get("normalization_stats") or {}

    hand = image_features(image_path, use_mediapipe=use_mediapipe)
    style = load_style_features(style_code)
    raw_features = {**hand, **{k: v for k, v in style.items() if k.startswith("style_")}}
    normalized = p5p95_normalize(raw_features, stats, feature_cols)
    x = np.array([[normalized[col] for col in feature_cols]], dtype=np.float32)
    model_score = float(model.predict(x)[0])
    model_score = max(0.0, min(100.0, model_score))
    score, hand_fit_score = blend_score_with_mediapipe(model_score, raw_features, style, feature_cols)
    score = max(0.0, min(100.0, score))

    skin_tone_score = 65 + normalized["brightness_mean"] * 30 + normalized["style_color_warmth"] * 5
    if hand_fit_score is not None:
        fingertip_brightness = clamp((raw_features["fingertip_brightness"] - 75.0) / 130.0)
        fingertip_redness = clamp(raw_features["fingertip_redness"] / 45.0)
        skin_tone_score = 58 + fingertip_brightness * 28 + normalized["style_color_warmth"] * 8 + fingertip_redness * 4

    metrics = {
        "手型适配度": round(hand_fit_score if hand_fit_score is not None else score, 1),
        "肤色显白度": round(skin_tone_score, 1),
        "风格匹配度": round(60 + normalized["style_edge_roundness"] * 20 + normalized["style_gloss"] * 15, 1),
        "场景实用性": round(70 + (1 - normalized["style_complexity"]) * 18, 1),
        "整体美观度": round(score * 0.72 + normalized["style_gloss"] * 18 + normalized["brightness_std"] * 10, 1),
    }
    metrics = {k: max(0, min(100, v)) for k, v in metrics.items()}

    print(json.dumps({
        "score": round(score, 1),
        "metrics": metrics,
        "rawFeatures": raw_features,
        "normalizedFeatures": normalized,
        "handAnalysis": {
            "mediapipeEnabled": use_mediapipe,
            "mediapipeDetected": raw_features["mediapipe_detected"] >= 0.5,
            "landmarkCount": 21 if raw_features["mediapipe_detected"] >= 0.5 else 0,
            "modelScore": round(model_score, 1),
            "handFitScore": round(hand_fit_score, 1) if hand_fit_score is not None else None,
            "modelUsesMediapipeFeatures": model_uses_mediapipe(feature_cols),
        },
        "styleCode": style_code,
        "styleName": style["style_name"],
        "model": model_path.name,
        "normalization": "p5/p95",
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
