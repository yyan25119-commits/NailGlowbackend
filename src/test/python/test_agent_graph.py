import sys
import unittest
from pathlib import Path
from unittest.mock import patch


PYTHON_DIR = Path(__file__).resolve().parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_DIR))

import agent_graph


class AgentGraphTests(unittest.TestCase):
    def test_origin_can_be_extracted_from_natural_language(self):
        self.assertEqual(agent_graph.extract_origin_from_message("我在南昌西站，怎么去你们店"), "南昌西站")
        self.assertEqual(agent_graph.extract_origin_from_message("从红谷滩万达去门店怎么走"), "红谷滩万达")

    def test_route_intent_is_delegated_to_route_subgraph(self):
        route_result = {
            "ok": True,
            "summary": "推荐驾车到店，约 4.2 公里，预计 12 分钟。",
            "navigationUrl": "https://uri.amap.com/navigation?demo=1",
            "routeSteps": ["沿示例道路行驶"],
        }

        with patch.object(agent_graph.route_agent, "run_agent", return_value=route_result) as route_mock:
            result = agent_graph.run_agent(
                {
                    "message": "从红谷滩万达去店里怎么走",
                    "origin": "红谷滩万达",
                    "destination": "NailGlow 市中心旗舰店",
                    "routeMode": "driving",
                }
            )

        self.assertEqual(result["intent"], "fulfillment")
        self.assertEqual(result["answer"], route_result["summary"])
        self.assertEqual(result["delegatedRoute"], route_result)
        self.assertEqual(result["specialistAgent"], "fulfillment_agent")
        self.assertEqual(result["activeAgent"], "fulfillment_agent")
        self.assertEqual(result["responseAgentName"], "门店履约 Agent")
        self.assertTrue(any(event["agentKey"] == "fulfillment_agent" for event in result["agentEvents"]))
        self.assertNotEqual(result["agentEvents"][-1]["agentKey"], "customer_agent")
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

        with patch.object(agent_graph.specialist_agents, "run", return_value=customer_result) as specialist_mock:
            with patch.object(agent_graph.route_agent, "run_agent") as route_mock:
                result = agent_graph.run_agent({"message": "明天下午三点预约"})

        self.assertEqual(result["status"], "tool_calls")
        self.assertEqual(result["toolCalls"], customer_result["toolCalls"])
        self.assertEqual(result["specialistAgent"], "appointment_agent")
        self.assertEqual(result["activeAgent"], "appointment_agent")
        specialist_mock.assert_called_once()
        self.assertNotIn("delegatedRoute", result)
        route_mock.assert_not_called()

    def test_retrieved_knowledge_is_injected_only_into_customer_agent(self):
        retrieval = {
            "available": True,
            "retrievalMode": "local_hybrid_fusion",
            "contextText": "【预约知识】当前账号只能保留一条有效预约。",
            "hits": [
                {
                    "title": "预约、改约与排队规则",
                    "sectionPath": "单账号有效预约规则",
                }
            ],
        }
        customer_result = {"answer": "我已理解你的预约问题。", "intent": "appointment", "toolCalls": []}

        with patch.object(agent_graph.rag_knowledge_base, "retrieve_for_chat", return_value=retrieval):
            with patch.object(agent_graph.specialist_agents, "run", return_value=customer_result) as customer_mock:
                result = agent_graph.run_agent({"mode": "appointment", "message": "我想改约"})

        self.assertEqual(customer_mock.call_args.args[0], "appointment_agent")
        payload = customer_mock.call_args.args[1]
        self.assertTrue(payload["ragContext"]["available"])
        self.assertIn("只能保留一条有效预约", payload["ragContext"]["contextText"])
        self.assertEqual(result["ragRetrievalMode"], "local_hybrid_fusion")
        self.assertEqual(result["ragSources"][0]["title"], "预约、改约与排队规则")

    def test_beauty_and_aftersales_have_distinct_specialists(self):
        with patch.object(
            agent_graph.specialist_agents,
            "run",
            return_value={"answer": "已处理", "intent": "presale", "toolCalls": []},
        ) as specialist_mock:
            beauty = agent_graph.run_agent({"message": "帮我推荐显白的美甲款式"})
            self.assertEqual(beauty["specialistAgent"], "beauty_advisor_agent")
            self.assertEqual(beauty["activeAgent"], "beauty_advisor_agent")

            aftersales = agent_graph.run_agent({"message": "我的美甲翘边了，需要售后"})
            self.assertEqual(aftersales["specialistAgent"], "aftersales_agent")
            self.assertEqual(aftersales["activeAgent"], "aftersales_agent")
            self.assertEqual(aftersales["agentEvents"][-1]["agentKey"], "aftersales_agent")

        self.assertEqual(specialist_mock.call_count, 2)

    def test_specialist_session_state_persists_and_unrelated_turn_returns_to_customer(self):
        with patch.object(agent_graph.rag_knowledge_base, "retrieve_for_chat", return_value={"available": False, "hits": []}):
            with patch.object(
                agent_graph.specialist_agents,
                "run",
                return_value={"answer": "已记录翘边问题", "intent": "aftersale", "toolCalls": []},
            ):
                aftersale = agent_graph.run_agent({"message": "我的美甲翘边了"})

            session = aftersale["agentSessionState"]
            self.assertEqual(session["currentAgent"], "aftersales_agent")
            self.assertEqual(session["specialists"]["aftersales_agent"]["turnCount"], 1)

            with patch.object(
                agent_graph.specialist_agents,
                "run",
                return_value={"answer": "你好，还想咨询什么？", "intent": "general", "toolCalls": []},
            ):
                general = agent_graph.run_agent({
                    "message": "你好，今天有什么活动",
                    "context": {"agentSessionState": session},
                })

            self.assertEqual(general["specialistAgent"], "customer_agent")
            self.assertEqual(general["activeAgent"], "customer_agent")
            self.assertEqual(general["agentSessionState"]["previousAgent"], "aftersales_agent")

    def test_customer_semantic_intent_can_trigger_second_stage_handoff(self):
        def fake_run(agent_key, _payload):
            if agent_key == "customer_agent":
                return {"answer": "正在转交路线规划", "intent": "route", "routeOrigin": "", "toolCalls": []}
            return {"answer": "unexpected", "intent": "general", "toolCalls": []}

        with patch.object(agent_graph.rag_knowledge_base, "retrieve_for_chat", return_value={"available": False, "hits": []}):
            with patch.object(agent_graph.specialist_agents, "run", side_effect=fake_run):
                result = agent_graph.run_agent({"message": "店里的位置怎么安排比较方便"})

        self.assertEqual(result["specialistAgent"], "fulfillment_agent")
        self.assertEqual(result["activeAgent"], "fulfillment_agent")
        self.assertTrue(any("语义识别" in event["detail"] for event in result["agentEvents"]))


if __name__ == "__main__":
    unittest.main()
