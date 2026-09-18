import sys
import unittest
from pathlib import Path


PYTHON_DIR = Path(__file__).resolve().parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_DIR))

import agent_content_gate


class AgentContentGateTests(unittest.TestCase):
    def test_unwraps_nested_protocol_json_before_display(self):
        raw = {
            "answer": '{"answer":"结合手型和场景，我建议细法式。","intent":"presale","toolCalls":[],"recommendedStyles":[]}',
        }
        result = agent_content_gate.normalize_result(raw, {"mode": "presale"})

        self.assertEqual(result["answer"], "结合手型和场景，我建议细法式。")
        self.assertNotIn('"toolCalls"', result["answer"])
        self.assertEqual(result["intent"], "presale")

    def test_rejects_unparseable_protocol_like_answer(self):
        result = agent_content_gate.normalize_result(
            {"answer": '{"answer":"尚未结束", "intent":"presale", "toolCalls":'},
            {"mode": "presale"},
        )

        self.assertNotIn('"intent"', result["answer"])
        self.assertIn("美甲", result["answer"])

    def test_beauty_fallback_keeps_real_style_candidates(self):
        result = agent_content_gate.normalize_result(
            {"answer": '{"answer":"截断", "intent":"presale", "toolCalls":'},
            {
                "mode": "presale",
                "context": {
                    "styleCandidates": [
                        {"name": "奶油法式"},
                        {"name": "冰透裸粉渐变"},
                    ]
                },
            },
        )

        self.assertIn("奶油法式", result["answer"])
        self.assertIn("冰透裸粉渐变", result["answer"])


if __name__ == "__main__":
    unittest.main()
