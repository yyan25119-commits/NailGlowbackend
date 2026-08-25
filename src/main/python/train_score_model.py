#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import json
import shutil
import sys
from pathlib import Path

import joblib
import numpy as np
from sklearn.ensemble import RandomForestRegressor

from score_model_predict import DEFAULT_FEATURE_COLS, ROOT, bool_payload, image_features, load_style_features, p5p95_normalize


FEATURE_COLS = DEFAULT_FEATURE_COLS


def read_payload():
    return json.loads(sys.stdin.read() or "{}")


def resolve_path(value):
    path = Path(value or "").expanduser()
    if not path.is_absolute():
        path = (ROOT / path).resolve()
    return path


def normalization_stats(rows):
    stats = {}
    for col in FEATURE_COLS:
        values = np.array([float(row[col]) for row in rows], dtype=np.float32)
        stats[col] = {
            "p5": float(np.percentile(values, 5)),
            "p95": float(np.percentile(values, 95)),
        }
    return stats


def sample_features(sample, use_mediapipe=True):
    image_path = resolve_path(sample.get("imagePath"))
    if not image_path.exists():
        return None
    style_code = sample.get("styleCode") or "nail_01"
    hand = image_features(image_path, use_mediapipe=use_mediapipe)
    style = load_style_features(style_code)
    return {**hand, **{key: value for key, value in style.items() if key.startswith("style_")}}


def main():
    payload = read_payload()
    output_path = resolve_path(payload.get("outputPath"))
    fallback_path = resolve_path(payload.get("fallbackModelPath"))
    use_mediapipe = bool_payload(payload.get("useMediapipe"), True)
    rows = []
    targets = []
    for sample in payload.get("samples") or []:
        features = sample_features(sample, use_mediapipe=use_mediapipe)
        if not features:
            continue
        rows.append(features)
        targets.append(float(sample.get("targetScore") or 0))

    output_path.parent.mkdir(parents=True, exist_ok=True)
    if not rows:
        if fallback_path.exists():
            shutil.copy2(fallback_path, output_path)
        print(json.dumps({"sampleCount": 0, "validationScore": 0}, ensure_ascii=False))
        return

    stats = normalization_stats(rows)
    x = np.array([[p5p95_normalize(row, stats, FEATURE_COLS)[col] for col in FEATURE_COLS] for row in rows], dtype=np.float32)
    y = np.array(targets, dtype=np.float32)
    model = RandomForestRegressor(n_estimators=120, random_state=42, min_samples_leaf=1)
    model.fit(x, y)
    pred = model.predict(x)
    mae = float(np.mean(np.abs(pred - y)))
    validation_score = max(0.0, min(100.0, 100.0 - mae))
    joblib.dump({
        "model": model,
        "feature_cols": FEATURE_COLS,
        "normalization_stats": stats,
    }, output_path)
    print(json.dumps({
        "sampleCount": len(rows),
        "validationScore": round(validation_score, 2),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
