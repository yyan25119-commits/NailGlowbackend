import sys
import unittest
from pathlib import Path
from unittest.mock import patch


PYTHON_DIR = Path(__file__).resolve().parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_DIR))

import agent_graph


class AgentGraphTests(unittest.TestCase):
    def test_route_intent_is_delegated_to_route_subgraph(self):
        customer_result = {
            "answer": "我来帮你规划路线。",
            "intent": "route",
            "routeOrigin": "红谷滩万达",
            "toolCalls": [],
        }
        route_result = {
            "ok": True,
            "summary": "推荐驾车到店，约 4.2 公里，预计 12 分钟。",
            "navigationUrl": "https://uri.amap.com/navigation?demo=1",
            "routeSteps": ["沿示例道路行驶"],
        }

        with patch.object(agent_graph.customer_service_agent, "run_agent", return_value=customer_result):
            with patch.object(agent_graph.route_agent, "run_agent", return_value=route_result) as route_mock:
                result = agent_graph.run_agent(
                    {
                        "message": "从红谷滩万达去店里怎么走",
                        "destination": "NailGlow 市中心旗舰店",
                        "routeMode": "driving",
                    }
                )

        self.assertEqual(result["intent"], "route")
        self.assertEqual(result["answer"], route_result["summary"])
        self.assertEqual(result["delegatedRoute"], route_result)
        route_mock.assert_called_once()
        delegated_payload = route_mock.call_args.args[0]
        self.assertEqual(delegated_payload["origin"], "红谷滩万达")
        self.assertEqual(delegated_payload["destination"], "NailGlow 市中心旗舰店")

    def test_business_tool_calls_return_to_java(self):
        customer_result = {
            "status": "tool_calls",
            "toolCalls": [
                {
                    "id": "call_1",
                    "name": "create_or_reschedule_appointment",
                    "arguments": {"scheduledAtIso": "2026-08-26T15:00:00"},
                }
            ],
            "agentSource": "test_customer_agent",
        }

        with patch.object(agent_graph.customer_service_agent, "run_agent", return_value=customer_result):
            with patch.object(agent_graph.route_agent, "run_agent") as route_mock:
                result = agent_graph.run_agent({"message": "明天下午三点预约"})

        self.assertEqual(result["status"], "tool_calls")
        self.assertEqual(result["toolCalls"], customer_result["toolCalls"])
        self.assertNotIn("delegatedRoute", result)
        route_mock.assert_not_called()


if __name__ == "__main__":
    unittest.main()
