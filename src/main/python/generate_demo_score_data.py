#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""Generate deterministic questionnaire-style demo data for NailGlow V2.

The generated data is explicitly marked synthetic.  It models two surveys:

* hand-only semantic differentials (length, slenderness, width, softness);
* hand/style pair ratings across five dimensions plus an overall score.

Raw respondent rows are written to CSV, while aggregated hand/style samples are
written to JSON for ``train_score_model.py``.
"""

from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

from score_model_framework import (
    MORPHOLOGY_TARGETS,
    SCORE_TARGETS,
    STYLE_FEATURE_COLS,
    clamp,
    clamp_score,
)
from score_model_predict import DEFAULT_FEATURE_COLS, ROOT, load_style_features


GENERATOR_VERSION = "nailglow-demo-survey-v2"


def bounded_normal(rng, mean, std, low=0.0, high=1.0):
    return clamp(rng.normal(mean, std), low, high)


def create_rater_profiles(rng, count):
    return [
        {
            "raterId": f"demo_rater_{index + 1:03d}",
            "bias": float(rng.normal(0.0, 3.2)),
            "strictness": bounded_normal(rng, 0.5, 0.17),
            "lengthPreference": bounded_normal(rng, 0.52, 0.22),
            "roundnessPreference": bounded_normal(rng, 0.62, 0.20),
            "warmthPreference": bounded_normal(rng, 0.58, 0.23),
            "complexityPreference": bounded_normal(rng, 0.46, 0.24),
        }
        for index in range(count)
    ]


def hand_latent_profile(rng, hand_index):
    # A mixture gives the demo broad, non-uniform morphology coverage.
    cluster = hand_index % 4
    cluster_means = (
        (0.70, 0.72, 0.30, 0.45),
        (0.37, 0.34, 0.72, 0.72),
        (0.58, 0.45, 0.60, 0.36),
        (0.43, 0.68, 0.38, 0.66),
    )
    length, slenderness, width, softness = [
        bounded_normal(rng, mean, 0.12) for mean in cluster_means[cluster]
    ]
    return {
        "length": length,
        "slenderness": slenderness,
        "width": width,
        "softness": softness,
        "skinWarmth": bounded_normal(rng, 0.54, 0.22),
        "skinLightness": bounded_normal(rng, 0.62, 0.18),
        "poseQuality": bounded_normal(rng, 0.86, 0.09, 0.45, 1.0),
        "framing": bounded_normal(rng, 0.84, 0.10, 0.35, 1.0),
        "sharpness": bounded_normal(rng, 0.80, 0.12, 0.30, 1.0),
        "exposure": bounded_normal(rng, 0.83, 0.10, 0.35, 1.0),
    }


def hand_features(rng, latent, hand_index):
    width = 0.16 + latent["width"] * 0.22 + rng.normal(0, 0.007)
    palm_aspect = 0.90 + latent["slenderness"] * 0.95 + rng.normal(0, 0.035)
    palm_length = width * palm_aspect
    middle_length = 1.00 + latent["length"] * 0.95 + rng.normal(0, 0.035)
    index_length = middle_length * bounded_normal(rng, 0.90, 0.025, 0.80, 0.98)
    ring_length = middle_length * bounded_normal(rng, 0.86, 0.03, 0.75, 0.96)
    pinky_length = middle_length * bounded_normal(rng, 0.66, 0.035, 0.54, 0.78)
    thumb_length = middle_length * bounded_normal(rng, 0.62, 0.04, 0.48, 0.78)
    finger_values = np.asarray([index_length, middle_length, ring_length, pinky_length])
    finger_balance = 1.0 - min(1.0, float(finger_values.std() / finger_values.mean()))
    brightness = 78 + latent["skinLightness"] * 138 + rng.normal(0, 4.0)
    redness = -4 + latent["skinWarmth"] * 40 + rng.normal(0, 2.0)
    lab_l = 38 + latent["skinLightness"] * 44 + rng.normal(0, 1.8)
    lab_a = 5 + latent["skinWarmth"] * 18 + rng.normal(0, 1.0)
    lab_b = 9 + latent["skinWarmth"] * 22 + rng.normal(0, 1.4)
    image_width = int(rng.choice([720, 828, 1080, 1170, 1440]))
    image_height = int(image_width * rng.uniform(1.18, 1.55))
    bbox_width = width * rng.uniform(1.75, 2.05)
    bbox_height = bbox_width * (1.02 + latent["length"] * 0.70)

    features = {
        "img_width": float(image_width),
        "img_height": float(image_height),
        "aspect_ratio": float(image_width / image_height),
        "brightness_mean": brightness + rng.normal(0, 5.0),
        "brightness_std": 30 + latent["skinLightness"] * 22 + rng.normal(0, 3.0),
        "r_mean": brightness + 10 + latent["skinWarmth"] * 8,
        "g_mean": brightness - 2,
        "b_mean": brightness - 8 - latent["skinWarmth"] * 5,
        "skin_mask_ratio": bounded_normal(rng, 0.43, 0.08, 0.18, 0.72),
        "center_brightness": brightness + rng.normal(0, 4.0),
        "mediapipe_detected": 1.0,
        "hand_detection_confidence": latent["poseQuality"],
        "hand_bbox_width": bbox_width,
        "hand_bbox_height": bbox_height,
        "hand_bbox_area": bbox_width * bbox_height,
        "hand_bbox_aspect": bbox_width / max(bbox_height, 1e-6),
        "palm_width_ratio": width,
        "palm_length_ratio": palm_length,
        "finger_length_ratio": float(finger_values.mean() / max(palm_aspect, 1e-6)),
        "finger_spread_ratio": 1.12 + (1.0 - latent["softness"]) * 0.88 + rng.normal(0, 0.05),
        "thumb_openness_ratio": 0.52 + (1.0 - latent["softness"]) * 0.55 + rng.normal(0, 0.035),
        "landmark_z_range": 0.04 + (1.0 - latent["poseQuality"]) * 0.16 + rng.normal(0, 0.008),
        "fingertip_brightness": brightness,
        "fingertip_redness": redness,
        "hand_length_ratio": palm_aspect + middle_length,
        "palm_aspect_ratio": palm_aspect,
        "thumb_finger_length_ratio": thumb_length,
        "index_finger_length_ratio": index_length,
        "middle_finger_length_ratio": middle_length,
        "ring_finger_length_ratio": ring_length,
        "pinky_finger_length_ratio": pinky_length,
        "finger_length_balance": finger_balance,
        "index_middle_span_ratio": 0.30 + (1.0 - latent["softness"]) * 0.30 + rng.normal(0, 0.02),
        "ring_pinky_span_ratio": 0.24 + (1.0 - latent["softness"]) * 0.25 + rng.normal(0, 0.02),
        "thumb_angle_ratio": 0.19 + (1.0 - latent["softness"]) * 0.24 + rng.normal(0, 0.015),
        "hand_center_x": bounded_normal(rng, 0.50, 0.055, 0.32, 0.68),
        "hand_center_y": bounded_normal(rng, 0.52, 0.06, 0.30, 0.72),
        "hand_framing_score": latent["framing"],
        "image_sharpness": latent["sharpness"],
        "exposure_balance": latent["exposure"],
        "skin_lab_l": lab_l,
        "skin_lab_a": lab_a,
        "skin_lab_b": lab_b,
    }
    # Stable, tiny hand-level offset keeps images from looking mathematically cloned.
    features["center_brightness"] += (hand_index % 7 - 3) * 0.35
    return features


def latent_morphology(latent):
    return {
        "perceived_length": clamp_score(8 + latent["length"] * 88),
        "perceived_slenderness": clamp_score(8 + latent["slenderness"] * 88),
        "perceived_palm_width": clamp_score(8 + latent["width"] * 88),
        "perceived_softness": clamp_score(8 + latent["softness"] * 88),
    }


def base_pair_scores(latent, style):
    target_length = clamp(0.22 + latent["length"] * 0.50 + latent["slenderness"] * 0.12)
    target_roundness = clamp(0.43 + latent["width"] * 0.31 + latent["softness"] * 0.24)
    target_warmth = clamp(0.24 + latent["skinWarmth"] * 0.64)
    length_match = 1.0 - abs(style["style_length"] - target_length)
    roundness_match = 1.0 - abs(style["style_edge_roundness"] - target_roundness)
    warmth_match = 1.0 - abs(style["style_color_warmth"] - target_warmth)
    complexity_target = clamp(0.28 + latent["slenderness"] * 0.40 + (1.0 - latent["softness"]) * 0.12)
    complexity_match = 1.0 - abs(style["style_complexity"] - complexity_target)

    hand_fit = 32 + length_match * 34 + roundness_match * 25 + latent["poseQuality"] * 7
    skin_tone = 36 + warmth_match * 38 + latent["skinLightness"] * 12 + style["style_gloss"] * 8
    style_match = 30 + complexity_match * 31 + roundness_match * 20 + style["style_gloss"] * 13
    scene_fit = 80 - style["style_complexity"] * 25 + (1.0 - abs(style["style_length"] - 0.46)) * 14
    aesthetic = hand_fit * 0.32 + skin_tone * 0.20 + style_match * 0.28 + scene_fit * 0.10 + style["style_gloss"] * 10
    return {
        "hand_fit": clamp_score(hand_fit),
        "skin_tone": clamp_score(skin_tone),
        "style_match": clamp_score(style_match),
        "scene_fit": clamp_score(scene_fit),
        "aesthetic": clamp_score(aesthetic),
    }


def respondent_scores(rng, latent, style, base_scores, rater):
    preference_adjustment = (
        (1.0 - abs(style["style_length"] - rater["lengthPreference"])) * 4.0
        + (1.0 - abs(style["style_edge_roundness"] - rater["roundnessPreference"])) * 3.0
        + (1.0 - abs(style["style_color_warmth"] - rater["warmthPreference"])) * 3.0
        + (1.0 - abs(style["style_complexity"] - rater["complexityPreference"])) * 2.0
        - 8.0
    )
    noise_scale = 3.0 + rater["strictness"] * 2.5
    scores = {
        target: clamp_score(value + rater["bias"] + preference_adjustment + rng.normal(0, noise_scale))
        for target, value in base_scores.items()
    }
    overall = clamp_score(
        scores["hand_fit"] * 0.30
        + scores["skin_tone"] * 0.18
        + scores["style_match"] * 0.20
        + scores["scene_fit"] * 0.10
        + scores["aesthetic"] * 0.22
        + rng.normal(0, 1.8)
    )
    morphology = {
        "perceived_length": clamp_score(8 + latent["length"] * 88 + rater["bias"] * 0.45 + rng.normal(0, 4.0)),
        "perceived_slenderness": clamp_score(8 + latent["slenderness"] * 88 + rater["bias"] * 0.45 + rng.normal(0, 4.3)),
        "perceived_palm_width": clamp_score(8 + latent["width"] * 88 - rater["bias"] * 0.25 + rng.normal(0, 4.1)),
        "perceived_softness": clamp_score(8 + latent["softness"] * 88 + rng.normal(0, 4.5)),
    }
    return morphology, scores, overall


def load_styles():
    style_codes = []
    with (ROOT / "nail_styles.csv").open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            style_codes.append(row["style_id"])
    result = []
    for style_code in style_codes:
        style = load_style_features(style_code)
        style["style_id"] = style_code
        result.append(style)
    return result


def mean_mapping(rows, names):
    return {
        name: round(float(np.mean([row[name] for row in rows])), 4)
        for name in names
    }


def generate(output_dir: Path, hand_count: int, rater_count: int, ratings_per_pair: int, seed: int):
    rng = np.random.default_rng(seed)
    output_dir.mkdir(parents=True, exist_ok=True)
    styles = load_styles()
    raters = create_rater_profiles(rng, rater_count)
    raw_responses = []
    aggregated_samples = []

    for hand_index in range(hand_count):
        hand_id = f"demo_hand_{hand_index + 1:04d}"
        latent = hand_latent_profile(rng, hand_index)
        hand_feature_values = hand_features(rng, latent, hand_index)
        for style in styles:
            pair_responses = []
            selected_raters = rng.choice(raters, size=min(ratings_per_pair, len(raters)), replace=False)
            for rater in selected_raters:
                morphology, scores, overall = respondent_scores(
                    rng, latent, style, base_pair_scores(latent, style), rater
                )
                response = {
                    "raterId": rater["raterId"],
                    "handId": hand_id,
                    "styleCode": style["style_id"],
                    **morphology,
                    **scores,
                    "overallScore": overall,
                }
                pair_responses.append(response)
                raw_responses.append(response)

            features = dict(hand_feature_values)
            features.update({key: float(style[key]) for key in STYLE_FEATURE_COLS})
            features = {column: float(features.get(column, 0.0)) for column in DEFAULT_FEATURE_COLS}
            aggregated_samples.append({
                "groupId": hand_id,
                "handId": hand_id,
                "styleCode": style["style_id"],
                "features": features,
                "morphologyTargets": mean_mapping(pair_responses, MORPHOLOGY_TARGETS),
                "targets": mean_mapping(pair_responses, SCORE_TARGETS),
                "overallScore": round(float(np.mean([item["overallScore"] for item in pair_responses])), 4),
                "ratingCount": len(pair_responses),
                "synthetic": True,
            })

    raw_path = output_dir / "questionnaire_responses_v2.csv"
    with raw_path.open("w", encoding="utf-8-sig", newline="") as stream:
        fieldnames = [
            "raterId", "handId", "styleCode", *MORPHOLOGY_TARGETS,
            *SCORE_TARGETS, "overallScore",
        ]
        writer = csv.DictWriter(stream, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(raw_responses)

    dataset_path = output_dir / "training_samples_v2.json"
    dataset = {
        "metadata": {
            "schemaVersion": GENERATOR_VERSION,
            "synthetic": True,
            "seed": seed,
            "handCount": hand_count,
            "styleCount": len(styles),
            "raterCount": rater_count,
            "ratingsPerPair": ratings_per_pair,
            "responseCount": len(raw_responses),
            "sampleCount": len(aggregated_samples),
            "createdAt": datetime.now(timezone.utc).isoformat(),
            "notice": "DEMO ONLY - synthetic questionnaire responses, not real user research",
        },
        "samples": aggregated_samples,
    }
    dataset_path.write_text(json.dumps(dataset, ensure_ascii=False, indent=2), encoding="utf-8")
    return raw_path, dataset_path, dataset["metadata"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", default="models/demo_data")
    parser.add_argument("--hands", type=int, default=180)
    parser.add_argument("--raters", type=int, default=48)
    parser.add_argument("--ratings-per-pair", type=int, default=7)
    parser.add_argument("--seed", type=int, default=20260827)
    args = parser.parse_args()
    raw_path, dataset_path, metadata = generate(
        resolve_output(args.output_dir),
        max(20, args.hands),
        max(7, args.raters),
        max(3, args.ratings_per_pair),
        args.seed,
    )
    print(json.dumps({
        "rawResponsesPath": str(raw_path),
        "trainingDatasetPath": str(dataset_path),
        **metadata,
    }, ensure_ascii=False))


def resolve_output(value):
    path = Path(value).expanduser()
    return path.resolve() if path.is_absolute() else (ROOT / path).resolve()


if __name__ == "__main__":
    main()
