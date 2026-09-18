# NailGlow Score Model V2

## Scope

Score Model V2 is a demo-oriented, two-stage recommendation framework. It is
not a claim of completed real-user research. The checked-in demo dataset is
synthetic and is marked as such in every generated artifact.

The model answers two different questions:

1. How is a hand perceived on continuous morphology axes?
2. How compatible is a specific hand/style pair across five dimensions?

Image generation remains a separate visual-preview service. Score Model V2
scores hand/style suitability; it does not score the quality of the generated
try-on image.

## Architecture

```text
hand image
  -> MediaPipe 21-landmark extraction with four-orientation recovery
  -> robust image, color, pose and hand-geometry features
  -> morphology model
       -> perceived length
       -> perceived slenderness
       -> perceived palm width
       -> perceived softness
  -> hand/style interaction features
  -> multi-output compatibility model
       -> hand fit
       -> skin-tone coordination
       -> style match
       -> scene fit
       -> aesthetics
  -> learned non-negative overall weights
  -> score + confidence + reasons, or an explicit unscored result
```

Training and serving share transformations from
`src/main/python/score_model_framework.py` to avoid feature-contract drift.

## Questionnaire contract

The data contract supports two survey layers:

- hand-only 0-100 semantic differentials for the four morphology targets;
- hand/style 0-100 ratings for the five compatibility targets and an overall
  recommendation score.

`groupId` or `handId` is mandatory for trustworthy validation. All variants of
the same hand stay in one GroupKFold partition.

An aggregated training sample has this shape:

```json
{
  "groupId": "hand_001",
  "handId": "hand_001",
  "styleCode": "nail_01",
  "features": { "palm_aspect_ratio": 1.32 },
  "morphologyTargets": {
    "perceived_length": 68,
    "perceived_slenderness": 72,
    "perceived_palm_width": 34,
    "perceived_softness": 57
  },
  "targets": {
    "hand_fit": 86,
    "skin_tone": 79,
    "style_match": 83,
    "scene_fit": 76,
    "aesthetic": 85
  },
  "overallScore": 83,
  "ratingCount": 7
}
```

## Validation

V2 no longer reports an in-sample training residual as validation. It uses
GroupKFold by hand and records:

- MAE, RMSE, R2 and Spearman correlation for each morphology target;
- MAE, RMSE, R2 and Spearman correlation for each score target;
- overall MAE, RMSE, R2, Spearman and NDCG@3.

The model bundle stores the full cross-validation report, robust scaling
statistics, feature importance, questionnaire counts, group counts, dataset
source and the deterministic seed.

## Confidence and rejection

Confidence combines:

- hand detection and image quality;
- p01/p99 training-distribution coverage;
- random-forest ensemble agreement;
- sample-count support.

If MediaPipe does not detect a hand or confidence is below the model threshold,
the response is `scored=false`. The system keeps the generated try-on image but
does not fabricate a fallback score.

## Deterministic demo training

From `backend/`:

```bash
.venv/bin/python src/main/python/generate_demo_score_data.py \
  --output-dir models/demo_data \
  --hands 180 \
  --raters 48 \
  --ratings-per-pair 7 \
  --seed 20260827
```

Then send this JSON to `train_score_model.py` on stdin:

```json
{
  "datasetPath": "models/demo_data/training_samples_v2.json",
  "outputPath": "score_model.joblib",
  "reportPath": "models/demo_data/training_report_v2.json",
  "backupExisting": true,
  "backupDir": "models/backups",
  "backupName": "score_model_pre_v2_20260827.joblib",
  "useMediapipe": false,
  "seed": 20260827,
  "confidenceThreshold": 0.45
}
```

Generated artifacts are retained for inspection. Do not describe their metrics
as real-user outcomes; they only verify the pipeline and interview demo.

## Real-feedback retraining

The admin retraining endpoint aggregates the existing five-dimensional customer
photo ratings without collapsing them into one target. It trains a candidate
model, stores cross-validation metadata and applies configurable activation
gates for sample count, independent hand groups and validation score.

Real feedback currently lacks a separate hand-only morphology questionnaire.
Until that survey is added, retraining uses a deterministic morphology fallback
for those labels. This limitation must remain visible in product and interview
explanations.
