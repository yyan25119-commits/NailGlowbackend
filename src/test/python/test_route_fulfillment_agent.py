import sys
import unittest
from pathlib import Path
from unittest.mock import patch


PYTHON_DIR = Path(__file__).resolve().parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_DIR))

import route_agent


class RouteFulfillmentAgentTests(unittest.TestCase):
    def payload(self):
        return {
            "message": "我下午三点有事，A店满了，帮我找能做完的店并自动预约",
            "origin": "115.88,28.68",
            "finishBy": "2026-08-28T15:00:00",
            "autoBook": True,
            "styleName": "显白通勤款",
            "context": {
                "currentServerTime": "2026-08-28 12:00",
                "serviceDurationMinutes": 90,
                "amount": 268,
                "storeCandidates": [
                    {
                        "storeId": "store_a",
                        "storeName": "A店",
                        "address": "A店地址",
                        "distanceKm": 2.0,
                        "drivingMinutes": 10,
                        "transitMinutes": 25,
                        "availableSlots": [],
                        "supportsStyle": True,
                    },
                    {
                        "storeId": "store_b",
                        "storeName": "B店",
                        "address": "B店地址",
                        "distanceKm": 3.5,
                        "drivingMinutes": 14,
                        "transitMinutes": 28,
                        "availableSlots": ["2026-08-28T13:00:00"],
                        "supportsStyle": True,
                        "parkingAvailable": True,
                        "parkingFeePerHour": 4,
                    },
                ],
            },
        }

    def test_selects_feasible_alternative_store_and_requests_booking(self):
        with patch.object(route_agent, "AMAP_KEY", ""):
            result = route_agent.evaluate_store_fulfillment(self.payload())

        self.assertTrue(result["ok"])
        self.assertEqual(result["recommendedStoreName"], "B店")
        self.assertEqual(result["serviceEndAt"], "2026-08-28T14:30:00")
        self.assertEqual(result["status"], "tool_calls")
        self.assertEqual(result["toolCalls"][0]["name"], "create_or_reschedule_appointment")
        self.assertEqual(result["toolCalls"][0]["arguments"]["storeName"], "B店")

    def test_returns_explainable_failure_when_deadline_is_impossible(self):
        payload = self.payload()
        payload["finishBy"] = "2026-08-28T13:30:00"
        with patch.object(route_agent, "AMAP_KEY", ""):
            result = route_agent.evaluate_store_fulfillment(payload)

        self.assertFalse(result["ok"])
        self.assertEqual(result["toolCalls"], [])
        self.assertTrue(result["storeCandidates"])

    def test_normalizes_amap_live_weather_and_applies_weather_buffer(self):
        weather_response = {
            "status": "1",
            "lives": [{
                "province": "江西",
                "city": "南昌市",
                "adcode": "360100",
                "weather": "中雨",
                "temperature": "27",
                "winddirection": "东北",
                "windpower": "4",
                "humidity": "81",
                "reporttime": "2026-08-28 12:00:00",
            }],
        }
        with patch.object(route_agent, "AMAP_KEY", "demo-key"):
            with patch.object(route_agent, "http_json", return_value=weather_response):
                weather = route_agent.fetch_weather_for_store({}, {"adcode": "360100"}, {})

        effect = route_agent.weather_route_effect(weather, "driving")
        self.assertEqual(weather["source"], "amap_live")
        self.assertEqual(weather["weather"], "中雨")
        self.assertGreater(effect["extraMinutes"], 0)
        self.assertEqual(effect["risk"], "medium")


if __name__ == "__main__":
    unittest.main()
