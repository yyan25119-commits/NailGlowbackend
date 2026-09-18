import sys
import unittest
from pathlib import Path

import numpy as np
import joblib


PYTHON_DIR = Path(__file__).resolve().parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_DIR))

import generate_demo_score_data
import score_model_framework
import score_model_predict


class ScoreModelFrameworkTests(unittest.TestCase):
    def test_bundled_demo_model_uses_v2_contract_and_group_validation(self):
        bundle = joblib.load(Path(__file__).resolve().parents[3] / "score_model.joblib")

        self.assertEqual(bundle["schema_version"], score_model_framework.MODEL_SCHEMA_VERSION)
        self.assertEqual(len(bundle["compatibility_feature_cols"]), 56)
        self.assertTrue(bundle["cv_metrics"]["available"])
        self.assertEqual(bundle["cv_metrics"]["method"], "GroupKFold(5)")
        self.assertTrue(bundle["training_metadata"]["synthetic"])

    def test_compatibility_contract_has_pairwise_interactions(self):
        normalized = {column: 0.5 for column in score_model_predict.DEFAULT_FEATURE_COLS}
        normalized.update({
            "style_length": 0.7,
            "style_edge_roundness": 0.55,
            "style_color_warmth": 0.65,
            "mediapipe_detected": 1.0,
            "hand_detection_confidence": 0.9,
            "exposure_balance": 0.8,
            "image_sharpness": 0.8,
        })
        morphology = {
            "perceived_length": 72,
            "perceived_slenderness": 68,
            "perceived_palm_width": 38,
            "perceived_softness": 55,
        }

        features = score_model_framework.interaction_features(normalized, morphology)

        self.assertEqual(set(features), set(score_model_framework.INTERACTION_FEATURE_COLS))
        self.assertGreater(features["length_match"], 0.7)
        self.assertGreater(features["pose_reliability"], 0.75)

    def test_demo_features_cover_every_runtime_feature(self):
        rng = np.random.default_rng(20260827)
        latent = generate_demo_score_data.hand_latent_profile(rng, 0)
        features = generate_demo_score_data.hand_features(rng, latent, 0)
        style = score_model_predict.load_style_features("nail_01")
        features.update({
            key: float(style[key]) for key in score_model_framework.STYLE_FEATURE_COLS
        })

        missing = [
            column for column in score_model_predict.DEFAULT_FEATURE_COLS
            if column not in features
        ]

        self.assertEqual(missing, [])
        self.assertGreater(features["middle_finger_length_ratio"], 0)
        self.assertGreater(features["image_sharpness"], 0)

    def test_learned_overall_weights_are_nonnegative_and_normalized(self):
        dimensions = np.asarray([
            [80, 70, 75, 60, 85],
            [60, 85, 70, 80, 68],
            [90, 75, 88, 72, 91],
            [55, 64, 58, 92, 60],
            [76, 88, 82, 70, 84],
        ], dtype=np.float64)
        overall = dimensions @ np.asarray([0.30, 0.18, 0.20, 0.10, 0.22])

        weights = score_model_framework.learn_overall_weights(dimensions, overall)

        self.assertAlmostEqual(float(weights.sum()), 1.0, places=6)
        self.assertTrue(np.all(weights >= 0))
        self.assertAlmostEqual(float(weights[0]), 0.30, places=2)

    def test_low_quality_input_is_rejected_by_quality_component(self):
        low_quality = {
            "mediapipe_detected": 0,
            "hand_detection_confidence": 0,
            "exposure_balance": 0.1,
            "image_sharpness": 0.1,
            "hand_framing_score": 0.1,
        }
        high_quality = {
            "mediapipe_detected": 1,
            "hand_detection_confidence": 0.95,
            "exposure_balance": 0.9,
            "image_sharpness": 0.85,
            "hand_framing_score": 0.88,
        }

        self.assertLess(score_model_framework.input_quality_score(low_quality), 0.1)
        self.assertGreater(score_model_framework.input_quality_score(high_quality), 0.85)


if __name__ == "__main__":
    unittest.main()
