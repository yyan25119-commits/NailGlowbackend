import hashlib
import math
import sys
import tempfile
import unittest
from pathlib import Path


PYTHON_DIR = Path(__file__).resolve().parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_DIR))

import rag_knowledge_base as rag


class DeterministicEmbedder:
    """Offline test embedder; it never calls an external embedding API."""

    dimension = 16

    def embed_texts(self, texts):
        vectors = []
        for text in texts:
            values = [0.0] * self.dimension
            for token in rag.query_terms(text):
                index = int(hashlib.sha256(token.encode("utf-8")).hexdigest()[:8], 16) % self.dimension
                values[index] += 1.0
            norm = math.sqrt(sum(value * value for value in values)) or 1.0
            vectors.append([value / norm for value in values])
        return vectors


class RagKnowledgeBaseTests(unittest.TestCase):
    def config_for(self, directory: Path) -> rag.RagConfig:
        return rag.RagConfig(
            enabled=True,
            embedding_api_key="offline-test-only",
            embedding_base_url="https://example.invalid/v1",
            embedding_model="offline-test-model",
            embedding_multimodal=False,
            rerank_api_key="",
            rerank_base_url="https://example.invalid/v1",
            rerank_model="qwen3-rerank",
            rerank_api_style="auto",
            db_path=directory / "knowledge.db",
            manifest_path=directory / "knowledge.manifest.json",
            source_dir=rag.DEFAULT_SOURCE_DIR,
            collection_name="test_nailglow_customer_knowledge",
            timeout_seconds=10,
        )

    def test_structure_aware_chunking_keeps_source_metadata(self):
        documents = rag.load_documents(rag.DEFAULT_SOURCE_DIR)
        chunks = rag.build_chunks(documents)

        synthetic = [document for document in documents if document.source.startswith("synthetic_beauty_case_")]
        self.assertGreaterEqual(len(documents), 136)
        self.assertEqual(len(synthetic), 128)
        self.assertGreater(len(chunks), 128)
        self.assertTrue(all(chunk.section_path for chunk in chunks))
        self.assertTrue(all(chunk.parent_id for chunk in chunks))
        self.assertTrue(any("常见问法" in chunk.section_path for chunk in chunks))

    def test_offline_index_and_online_retrieval_work_without_real_api(self):
        with tempfile.TemporaryDirectory() as directory_name:
            config = self.config_for(Path(directory_name))
            embedder = DeterministicEmbedder()

            indexed = rag.build_index(config, embedder=embedder)
            self.assertTrue(indexed["ok"])
            self.assertGreater(indexed["chunks"], 10)
            self.assertTrue(config.db_path.exists())

            result = rag.retrieve_for_chat(
                {"mode": "appointment", "message": "我已经有预约，想改到明天下午三点"},
                config,
                embedder=embedder,
            )
            self.assertTrue(result["available"])
            self.assertEqual(result["retrievalMode"], "local_hybrid_fusion")
            self.assertIn("只能保留一条有效预约", result["contextText"])

            beauty = rag.retrieve_for_chat(
                {"mode": "recommendation", "message": "手指偏短，平时要用键盘，想要显白通勤款"},
                config,
                embedder=embedder,
            )
            self.assertTrue(beauty["available"])
            self.assertEqual(beauty["expectedDomain"], "presale")
            self.assertTrue(any(hit["domain"] == "presale" for hit in beauty["hits"]))

    def test_short_beauty_follow_up_is_enriched_from_agent_state(self):
        query = rag.retrieval_query({
            "message": "还有别的吗",
            "context": {
                "agentSessionState": {
                    "currentAgent": "beauty_advisor_agent",
                    "specialists": {
                        "beauty_advisor_agent": {
                            "lastUserMessage": "想要显白的通勤款",
                            "workingMemory": {
                                "handProfile": {"修长程度": 34, "掌宽感": 72},
                                "styleName": "裸粉细法式",
                            },
                        }
                    },
                }
            },
        })
        self.assertIn("修长程度:34", query)
        self.assertIn("显白的通勤款", query)


if __name__ == "__main__":
    unittest.main()
