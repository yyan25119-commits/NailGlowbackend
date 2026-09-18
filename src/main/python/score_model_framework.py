from __future__ import annotations

import math
from collections import defaultdict
from typing import Iterable, Mapping, Sequence

import numpy as np


MODEL_SCHEMA_VERSION = "nailglow-score-v2"

MORPHOLOGY_TARGETS = (
    "perceived_length",
    "perceived_slenderness",
    "perceived_palm_width",
    "perceived_softness",
)

SCORE_TARGETS = (
    "hand_fit",
    "skin_tone",
    "style_match",
    "scene_fit",
    "aesthetic",
)

SCORE_LABELS = {
    "hand_fit": "手型适配度",
    "skin_tone": "肤色协调度",
    "style_match": "风格匹配度",
    "scene_fit": "场景适用度",
    "aesthetic": "整体美观度",
}

MORPHOLOGY_LABELS = {
    "perceived_length": "修长程度",
    "perceived_slenderness": "纤细程度",
    "perceived_palm_width": "手掌宽度",
    "perceived_softness": "轮廓柔和度",
}

STYLE_FEATURE_COLS = (
    "style_length",
    "style_color_warmth",
    "style_complexity",
    "style_gloss",
    "style_edge_roundness",
)

# Raw resolution and RGB means are retained for legacy-model compatibility and
# diagnostics, but V2 excludes them from learning because they mainly encode
# camera/export conditions. Aspect, robust Lab colour and quality features carry
# the useful signal without encouraging device leakage.
V2_EXCLUDED_BASE_FEATURES = frozenset({
    "img_width",
    "img_height",
    "r_mean",
    "g_mean",
    "b_mean",
})

# Derived from normalized image/style features and predicted morphology.  These
# interactions encode the question we actually care about: not whether a hand
# or style is attractive in isolation, but whether the pair is compatible.
INTERACTION_FEATURE_COLS = (
    "morph_length",
    "morph_slenderness",
    "morph_palm_width",
    "morph_softness",
    "length_match",
    "roundness_match",
    "warmth_match",
    "slender_length_synergy",
    "width_roundness_synergy",
    "softness_gloss_synergy",
    "complexity_balance",
    "skin_style_contrast",
    "pose_reliability",
)

DEFAULT_OVERALL_WEIGHTS = np.asarray([0.30, 0.18, 0.20, 0.10, 0.22], dtype=np.float64)


def clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return max(low, min(high, float(value)))


def clamp_score(value: float) -> float:
    return max(0.0, min(100.0, float(value)))


def percentile_stats(
    rows: Sequence[Mapping[str, float]],
    columns: Sequence[str],
) -> dict[str, dict[str, float]]:
    """Fit robust scaling and OOD bounds used by both training and serving."""

    stats: dict[str, dict[str, float]] = {}
    for column in columns:
        values = np.asarray([float(row.get(column, 0.0)) for row in rows], dtype=np.float64)
        stats[column] = {
            "p01": float(np.percentile(values, 1)),
            "p05": float(np.percentile(values, 5)),
            "p50": float(np.percentile(values, 50)),
            "p95": float(np.percentile(values, 95)),
            "p99": float(np.percentile(values, 99)),
            "mean": float(values.mean()),
            "std": float(values.std()),
        }
    return stats


def normalize_row(
    row: Mapping[str, float],
    stats: Mapping[str, Mapping[str, float]],
    columns: Sequence[str],
) -> dict[str, float]:
    normalized: dict[str, float] = {}
    for column in columns:
        value = float(row.get(column, 0.0))
        item = stats.get(column, {})
        p05 = float(item.get("p05", item.get("p5", 0.0)))
        p95 = float(item.get("p95", 1.0))
        normalized[column] = 0.5 if abs(p95 - p05) < 1e-9 else clamp((value - p05) / (p95 - p05))
    return normalized


def heuristic_morphology(raw: Mapping[str, float]) -> dict[str, float]:
    """Fallback morphology for legacy/partially labelled real-world samples.

    Questionnaire-labelled morphology is preferred.  This deterministic
    mapping only keeps retraining possible while the demo collects labels.
    """

    eps = 1e-6
    palm_width = max(float(raw.get("palm_width_ratio", 0.22)), eps)
    palm_length = max(float(raw.get("palm_length_ratio", 0.24)), eps)
    palm_aspect = float(raw.get("palm_aspect_ratio", palm_length / palm_width))
    middle_length = float(raw.get("middle_finger_length_ratio", raw.get("finger_length_ratio", 0.75)))
    hand_length = float(raw.get("hand_length_ratio", palm_aspect + middle_length))
    finger_balance = float(raw.get("finger_length_balance", 0.78))
    spread = float(raw.get("finger_spread_ratio", 1.45))
    softness_proxy = (
        (1.0 - clamp(abs(spread - 1.45) / 1.45)) * 0.30
        + clamp(finger_balance) * 0.30
        + (1.0 - clamp(raw.get("landmark_z_range", 0.08) / 0.24)) * 0.15
        + 0.25
    )
    return {
        "perceived_length": clamp_score(28.0 + clamp((hand_length - 1.45) / 1.35) * 65.0),
        "perceived_slenderness": clamp_score(22.0 + clamp((palm_aspect - 0.85) / 1.10) * 70.0),
        "perceived_palm_width": clamp_score(18.0 + clamp((palm_width - 0.12) / 0.32) * 72.0),
        "perceived_softness": clamp_score(softness_proxy * 100.0),
    }


def morphology_array_to_dict(values: Sequence[float]) -> dict[str, float]:
    return {
        name: clamp_score(values[index])
        for index, name in enumerate(MORPHOLOGY_TARGETS)
    }


def interaction_features(
    normalized: Mapping[str, float],
    morphology: Mapping[str, float],
) -> dict[str, float]:
    morph_length = clamp(float(morphology.get("perceived_length", 50.0)) / 100.0)
    morph_slenderness = clamp(float(morphology.get("perceived_slenderness", 50.0)) / 100.0)
    morph_width = clamp(float(morphology.get("perceived_palm_width", 50.0)) / 100.0)
    morph_softness = clamp(float(morphology.get("perceived_softness", 50.0)) / 100.0)
    style_length = clamp(normalized.get("style_length", 0.5))
    style_roundness = clamp(normalized.get("style_edge_roundness", 0.5))
    style_warmth = clamp(normalized.get("style_color_warmth", 0.5))
    style_complexity = clamp(normalized.get("style_complexity", 0.5))
    style_gloss = clamp(normalized.get("style_gloss", 0.5))
    skin_warmth = clamp(normalized.get("skin_lab_a", normalized.get("fingertip_redness", 0.5)))
    skin_lightness = clamp(normalized.get("skin_lab_l", normalized.get("fingertip_brightness", 0.5)))
    detected = clamp(normalized.get("mediapipe_detected", 1.0))
    detection_confidence = clamp(normalized.get("hand_detection_confidence", 0.8))
    exposure = clamp(normalized.get("exposure_balance", 0.8))
    sharpness = clamp(normalized.get("image_sharpness", 0.7))

    # Long/slender hands are generally compatible with a broader range of long
    # styles; wider/softer profiles gain compatibility from rounder edges.  The
    # model is free to correct these priors using questionnaire labels.
    target_length = clamp(0.24 + morph_length * 0.48 + morph_slenderness * 0.12)
    target_roundness = clamp(0.44 + morph_width * 0.32 + morph_softness * 0.22)
    target_warmth = clamp(0.28 + skin_warmth * 0.58)

    return {
        "morph_length": morph_length,
        "morph_slenderness": morph_slenderness,
        "morph_palm_width": morph_width,
        "morph_softness": morph_softness,
        "length_match": 1.0 - abs(style_length - target_length),
        "roundness_match": 1.0 - abs(style_roundness - target_roundness),
        "warmth_match": 1.0 - abs(style_warmth - target_warmth),
        "slender_length_synergy": morph_slenderness * style_length,
        "width_roundness_synergy": morph_width * style_roundness,
        "softness_gloss_synergy": morph_softness * style_gloss,
        "complexity_balance": 1.0 - abs(style_complexity - (0.35 + morph_slenderness * 0.35)),
        "skin_style_contrast": abs(style_warmth - skin_warmth) * (0.45 + skin_lightness * 0.55),
        "pose_reliability": clamp(detected * 0.35 + detection_confidence * 0.30 + exposure * 0.20 + sharpness * 0.15),
    }


def compatibility_row(
    normalized: Mapping[str, float],
    morphology: Mapping[str, float],
    base_columns: Sequence[str],
) -> dict[str, float]:
    row = {column: float(normalized.get(column, 0.0)) for column in base_columns}
    row.update(interaction_features(normalized, morphology))
    return row


def compatibility_columns(base_columns: Sequence[str]) -> tuple[str, ...]:
    return tuple(base_columns) + INTERACTION_FEATURE_COLS


def learn_overall_weights(dimensions: np.ndarray, overall: np.ndarray) -> np.ndarray:
    if len(dimensions) < 2 or dimensions.ndim != 2 or dimensions.shape[1] != len(SCORE_TARGETS):
        return DEFAULT_OVERALL_WEIGHTS.copy()
    try:
        weights, *_ = np.linalg.lstsq(dimensions, overall, rcond=None)
    except np.linalg.LinAlgError:
        return DEFAULT_OVERALL_WEIGHTS.copy()
    weights = np.clip(np.asarray(weights, dtype=np.float64), 0.0, None)
    total = float(weights.sum())
    return DEFAULT_OVERALL_WEIGHTS.copy() if total <= 1e-9 else weights / total


def aggregate_overall(scores: Sequence[float], weights: Sequence[float]) -> float:
    values = np.asarray(scores, dtype=np.float64)
    normalized_weights = np.asarray(weights, dtype=np.float64)
    if len(values) != len(SCORE_TARGETS) or len(normalized_weights) != len(SCORE_TARGETS):
        normalized_weights = DEFAULT_OVERALL_WEIGHTS
    total = float(normalized_weights.sum())
    if total <= 1e-9:
        normalized_weights = DEFAULT_OVERALL_WEIGHTS
        total = float(normalized_weights.sum())
    return clamp_score(float(np.dot(values, normalized_weights / total)))


def forest_prediction_uncertainty(model, matrix: np.ndarray) -> float:
    estimators = getattr(model, "estimators_", None)
    if not estimators:
        return 0.5
    predictions = np.asarray([estimator.predict(matrix)[0] for estimator in estimators], dtype=np.float64)
    if predictions.ndim == 1:
        predictions = predictions[:, None]
    mean_std = float(np.mean(np.std(predictions, axis=0)))
    return clamp(1.0 - mean_std / 24.0)


def out_of_distribution_score(
    raw: Mapping[str, float],
    stats: Mapping[str, Mapping[str, float]],
    columns: Sequence[str],
) -> float:
    checked = 0
    inside = 0.0
    for column in columns:
        item = stats.get(column)
        if not item:
            continue
        checked += 1
        value = float(raw.get(column, 0.0))
        p01 = float(item.get("p01", item.get("p05", item.get("p5", value))))
        p99 = float(item.get("p99", item.get("p95", value)))
        if p01 <= value <= p99:
            inside += 1.0
        else:
            span = max(abs(p99 - p01), 1e-6)
            distance = min(abs(value - p01), abs(value - p99)) / span
            inside += max(0.0, 1.0 - distance)
    return 0.5 if checked == 0 else clamp(inside / checked)


def input_quality_score(raw: Mapping[str, float]) -> float:
    detected = 1.0 if float(raw.get("mediapipe_detected", 0.0)) >= 0.5 else 0.0
    confidence = clamp(raw.get("hand_detection_confidence", 0.0))
    exposure = clamp(raw.get("exposure_balance", 0.5))
    sharpness = clamp(raw.get("image_sharpness", 0.5))
    framing = clamp(raw.get("hand_framing_score", 0.5))
    return clamp(detected * 0.30 + confidence * 0.25 + exposure * 0.15 + sharpness * 0.15 + framing * 0.15)


def prediction_confidence(
    raw: Mapping[str, float],
    stats: Mapping[str, Mapping[str, float]],
    columns: Sequence[str],
    model,
    matrix: np.ndarray,
    sample_count: int,
) -> tuple[float, dict[str, float]]:
    quality = input_quality_score(raw)
    distribution = out_of_distribution_score(raw, stats, columns)
    certainty = forest_prediction_uncertainty(model, matrix)
    support = clamp(math.log10(max(1, sample_count)) / 3.0)
    confidence = clamp(quality * 0.38 + distribution * 0.27 + certainty * 0.25 + support * 0.10)
    return confidence, {
        "inputQuality": round(quality, 4),
        "distributionSupport": round(distribution, 4),
        "ensembleCertainty": round(certainty, 4),
        "sampleSupport": round(support, 4),
    }


def build_reasons(
    scores: Mapping[str, float],
    interactions: Mapping[str, float],
    confidence: float,
) -> list[str]:
    candidates: list[tuple[float, str]] = []
    candidates.append((float(interactions.get("length_match", 0.0)), "甲片长度与手指比例匹配"))
    candidates.append((float(interactions.get("roundness_match", 0.0)), "甲型轮廓能够平衡当前手掌宽度"))
    candidates.append((float(interactions.get("warmth_match", 0.0)), "款式色温与指尖肤色较协调"))
    candidates.append((float(interactions.get("complexity_balance", 0.0)), "款式复杂度与当前手型视觉量感协调"))
    candidates.append((float(scores.get("scene_fit", 0.0)) / 100.0, "场景适用度较稳定"))
    selected = [text for value, text in sorted(candidates, reverse=True) if value >= 0.66][:3]
    if confidence < 0.55:
        selected.append("图片质量或样本覆盖有限，本次评分置信度偏低")
    return selected or ["当前组合处于模型常见样本范围内"]


def rankdata(values: Sequence[float]) -> np.ndarray:
    values_array = np.asarray(values, dtype=np.float64)
    order = np.argsort(values_array, kind="mergesort")
    ranks = np.empty(len(values_array), dtype=np.float64)
    index = 0
    while index < len(order):
        end = index + 1
        while end < len(order) and values_array[order[end]] == values_array[order[index]]:
            end += 1
        average_rank = (index + end - 1) / 2.0
        ranks[order[index:end]] = average_rank
        index = end
    return ranks


def spearman_correlation(actual: Sequence[float], predicted: Sequence[float]) -> float:
    if len(actual) < 2:
        return 0.0
    actual_ranks = rankdata(actual)
    predicted_ranks = rankdata(predicted)
    if np.std(actual_ranks) <= 1e-12 or np.std(predicted_ranks) <= 1e-12:
        return 0.0
    return float(np.corrcoef(actual_ranks, predicted_ranks)[0, 1])


def ndcg_at_k(
    groups: Sequence[str],
    actual: Sequence[float],
    predicted: Sequence[float],
    k: int = 3,
) -> float:
    grouped: dict[str, list[tuple[float, float]]] = defaultdict(list)
    for group, actual_value, predicted_value in zip(groups, actual, predicted):
        grouped[str(group)].append((float(actual_value), float(predicted_value)))
    scores: list[float] = []
    for items in grouped.values():
        if len(items) < 2:
            continue
        ideal = sorted(items, key=lambda item: item[0], reverse=True)[:k]
        ranked = sorted(items, key=lambda item: item[1], reverse=True)[:k]

        def dcg(values: Iterable[tuple[float, float]]) -> float:
            return sum(
                (max(0.0, item[0]) / 100.0) / math.log2(position + 2)
                for position, item in enumerate(values)
            )

        ideal_score = dcg(ideal)
        scores.append(0.0 if ideal_score <= 1e-12 else dcg(ranked) / ideal_score)
    return float(np.mean(scores)) if scores else 0.0
