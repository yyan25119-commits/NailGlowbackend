#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""NailGlow customer-service RAG backed by Milvus Lite.

Offline mode:
    Markdown knowledge source -> structure-aware chunks -> embeddings -> Milvus Lite.

Online mode:
    Customer query -> dense recall -> lexical/domain fusion -> optional qwen3-rerank
    -> compact evidence for the customer agent.

No secret is stored in this module.  Keys are read only from process
environment variables and are never written to stdout, manifests, or logs.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Protocol, Sequence

try:
    from pymilvus import MilvusClient

    MILVUS_AVAILABLE = True
except ModuleNotFoundError:  # pragma: no cover - compatibility path
    MilvusClient = None  # type: ignore[assignment,misc]
    MILVUS_AVAILABLE = False


PROJECT_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_SOURCE_DIR = PROJECT_ROOT / "src" / "main" / "resources" / "rag" / "customer_service"
DEFAULT_RUNTIME_DIR = PROJECT_ROOT / "runtime" / "rag"
DEFAULT_DB_PATH = DEFAULT_RUNTIME_DIR / "nailglow_customer_knowledge.db"
DEFAULT_MANIFEST_PATH = DEFAULT_RUNTIME_DIR / "nailglow_customer_knowledge.manifest.json"
DEFAULT_COLLECTION = "nailglow_customer_knowledge"
SYNTHETIC_BEAUTY_SEED = "synthetic_beauty_case_matrix.json"

TARGET_CHARS = 460
MAX_CHARS = 620
OVERLAP_CHARS = 80
INITIAL_TOP_K = 12
RERANK_CANDIDATES = 8
FINAL_TOP_K = 4
EMBED_BATCH_SIZE = 32

FRONT_MATTER = re.compile(r"\A---\s*\n(?P<body>.*?)\n---\s*\n", re.DOTALL)
HEADING = re.compile(r"^(#{1,3})\s+(.+?)\s*$")
SENTENCE_BOUNDARY = re.compile(r"(?<=[。！？!?；;])\s*|\n+")
CJK = re.compile(r"[\u4e00-\u9fff]")
WORD = re.compile(r"[A-Za-z0-9_+-]+")


class RagError(RuntimeError):
    pass


class RagConfigurationError(RagError):
    pass


@dataclass(frozen=True)
class RagConfig:
    enabled: bool
    embedding_api_key: str
    embedding_base_url: str
    embedding_model: str
    embedding_multimodal: bool
    rerank_api_key: str
    rerank_base_url: str
    rerank_model: str
    rerank_api_style: str
    db_path: Path
    manifest_path: Path
    source_dir: Path
    collection_name: str
    timeout_seconds: float

    @classmethod
    def from_env(cls) -> "RagConfig":
        runtime_dir = Path(os.getenv("RAG_RUNTIME_DIR") or DEFAULT_RUNTIME_DIR)
        db_path = Path(os.getenv("RAG_MILVUS_URI") or runtime_dir / DEFAULT_DB_PATH.name)
        manifest_path = Path(os.getenv("RAG_MANIFEST_PATH") or runtime_dir / DEFAULT_MANIFEST_PATH.name)
        source_dir = Path(os.getenv("RAG_SOURCE_DIR") or DEFAULT_SOURCE_DIR)
        return cls(
            enabled=as_bool(os.getenv("RAG_ENABLED"), True),
            embedding_api_key=first_env("RAG_EMBEDDING_API_KEY", "DEEPSEEK_API_KEY"),
            embedding_base_url=(
                os.getenv("RAG_EMBEDDING_BASE_URL")
                or "https://ark.cn-beijing.volces.com/api/v3"
            ).rstrip("/"),
            embedding_model=os.getenv("RAG_EMBEDDING_MODEL") or "doubao-embedding-vision-251215",
            embedding_multimodal=as_bool(
                os.getenv("RAG_EMBEDDING_MULTIMODAL"),
                "embedding-vision" in (os.getenv("RAG_EMBEDDING_MODEL") or "doubao-embedding-vision-251215").lower(),
            ),
            rerank_api_key=first_env("DASHSCOPE_API_KEY", "RAG_RERANK_API_KEY"),
            rerank_base_url=(
                os.getenv("RAG_RERANK_BASE_URL")
                or "https://dashscope.aliyuncs.com/compatible-api/v1"
            ).rstrip("/"),
            rerank_model=os.getenv("RAG_RERANK_MODEL") or "qwen3-rerank",
            rerank_api_style=(os.getenv("RAG_RERANK_API_STYLE") or "auto").strip().lower(),
            db_path=db_path,
            manifest_path=manifest_path,
            source_dir=source_dir,
            collection_name=os.getenv("RAG_COLLECTION_NAME") or DEFAULT_COLLECTION,
            timeout_seconds=max(5.0, float(os.getenv("RAG_TIMEOUT_SECONDS", "45"))),
        )

    def safe_status(self) -> Dict[str, Any]:
        return {
            "enabled": self.enabled,
            "embeddingConfigured": bool(self.embedding_api_key),
            "embeddingBaseUrl": self.embedding_base_url,
            "embeddingModel": self.embedding_model,
            "embeddingMultimodal": self.embedding_multimodal,
            "rerankConfigured": bool(self.rerank_api_key),
            "rerankBaseUrl": self.rerank_base_url,
            "rerankModel": self.rerank_model,
            "rerankApiStyle": self.rerank_api_style,
            "milvusUri": str(self.db_path),
            "sourceDir": str(self.source_dir),
            "collection": self.collection_name,
        }


@dataclass(frozen=True)
class KnowledgeDocument:
    source: str
    title: str
    domain: str
    tags: List[str]
    body: str


@dataclass(frozen=True)
class KnowledgeChunk:
    id: int
    source: str
    title: str
    domain: str
    tags: List[str]
    section_path: str
    parent_id: str
    chunk_index: int
    text: str

    def entity(self, vector: Sequence[float]) -> Dict[str, Any]:
        return {
            "id": self.id,
            "vector": list(vector),
            "text": self.text,
            "source": self.source,
            "title": self.title,
            "domain": self.domain,
            "tags": ",".join(self.tags),
            "section_path": self.section_path,
            "parent_id": self.parent_id,
            "chunk_index": self.chunk_index,
        }


class Embedder(Protocol):
    def embed_texts(self, texts: Sequence[str]) -> List[List[float]]:
        ...


def as_bool(value: str | None, default: bool) -> bool:
    if value is None or not value.strip():
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def first_env(*names: str) -> str:
    for name in names:
        value = os.getenv(name)
        if value and value.strip():
            return value.strip()
    return ""


def stable_int(value: str) -> int:
    # Keep the primary key below signed 64-bit range required by Milvus INT64.
    return int(hashlib.sha256(value.encode("utf-8")).hexdigest()[:15], 16)


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def parse_front_matter(raw: str) -> tuple[Dict[str, str], str]:
    match = FRONT_MATTER.match(raw)
    if not match:
        return {}, raw
    metadata: Dict[str, str] = {}
    for line in match.group("body").splitlines():
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        metadata[key.strip().lower()] = value.strip().strip("[]").strip()
    return metadata, raw[match.end():]


def parse_tags(value: str) -> List[str]:
    return [item.strip().strip('"\'') for item in re.split(r"[,，]", value or "") if item.strip()]


def load_documents(source_dir: Path) -> List[KnowledgeDocument]:
    if not source_dir.exists():
        raise RagConfigurationError(f"RAG 知识源目录不存在：{source_dir}")
    documents: List[KnowledgeDocument] = []
    for path in sorted(source_dir.glob("*.md")):
        raw = path.read_text(encoding="utf-8")
        metadata, body = parse_front_matter(raw)
        title = metadata.get("title") or path.stem
        domain = metadata.get("domain") or "general"
        documents.append(
            KnowledgeDocument(
                source=path.name,
                title=title,
                domain=domain,
                tags=parse_tags(metadata.get("tags", "")),
                body=body.strip(),
            )
        )
    documents.extend(load_synthetic_beauty_documents(source_dir / SYNTHETIC_BEAUTY_SEED))
    if not documents:
        raise RagConfigurationError(f"RAG 知识源目录中没有 Markdown 文件：{source_dir}")
    return documents


def load_synthetic_beauty_documents(path: Path) -> List[KnowledgeDocument]:
    """Expand a small audited matrix into at least 100 deterministic demo cases."""

    if not path.exists():
        return []
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise RagConfigurationError(f"合成美甲案例矩阵格式错误：{path}")
    axes = {
        key: value if isinstance(value, list) else []
        for key, value in raw.items()
        if key in {"handProfiles", "skinTones", "scenes", "preferences"}
    }
    if any(not axes.get(key) for key in ("handProfiles", "skinTones", "scenes", "preferences")):
        raise RagConfigurationError("合成美甲案例矩阵缺少必需维度")

    documents: List[KnowledgeDocument] = []
    case_number = 0
    for hand in axes["handProfiles"]:
        for skin in axes["skinTones"]:
            for scene in axes["scenes"]:
                for preference in axes["preferences"]:
                    if not all(isinstance(item, dict) for item in (hand, skin, scene, preference)):
                        continue
                    case_number += 1
                    case_id = f"SG-{case_number:03d}"
                    title = f"合成美甲推荐案例 {case_id}"
                    body = (
                        f"# {title}\n\n"
                        f"【数据性质】本记录是 NailGlow Demo 的合成数据，不对应真实用户。\n\n"
                        f"【手型画像】{hand.get('label')}；量化区间：{hand.get('ranges')}。"
                        f"甲型策略：{hand.get('nailShape')}；修饰重点：{hand.get('strategy')}。\n\n"
                        f"【肤色画像】{skin.get('label')}。优先色盘：{skin.get('palette')}；"
                        f"建议试穿确认：{skin.get('caution')}。\n\n"
                        f"【使用场景】{scene.get('label')}。长度和饰品约束：{scene.get('constraints')}；"
                        f"维护重点：{scene.get('maintenance')}。\n\n"
                        f"【风格偏好】{preference.get('label')}。主款方向：{preference.get('primary')}；"
                        f"备选方向：{preference.get('secondary')}。\n\n"
                        f"【综合推荐】优先使用{hand.get('nailShape')}，从{skin.get('palette')}中选色，"
                        f"结合{preference.get('primary')}的风格语言，并遵守{scene.get('constraints')}。"
                        f"解释时同时说明手型修饰、肤色协调、场景可用性三个理由。"
                    )
                    documents.append(
                        KnowledgeDocument(
                            source=f"synthetic_beauty_case_{case_id}.json",
                            title=title,
                            domain="presale",
                            tags=[
                                "合成案例",
                                str(hand.get("id") or "hand"),
                                str(skin.get("id") or "skin"),
                                str(scene.get("id") or "scene"),
                                str(preference.get("id") or "preference"),
                            ],
                            body=body,
                        )
                    )
    if len(documents) < 100:
        raise RagConfigurationError(f"合成美甲案例少于 100 条：{len(documents)}")
    return documents


def split_sections(document: KnowledgeDocument) -> List[tuple[List[str], str]]:
    stack: List[tuple[int, str]] = []
    current_lines: List[str] = []
    sections: List[tuple[List[str], str]] = []

    def flush() -> None:
        text = "\n".join(current_lines).strip()
        if text:
            path = [document.title, *[title for _, title in stack]]
            sections.append((path, text))

    for line in document.body.splitlines():
        match = HEADING.match(line)
        if not match:
            current_lines.append(line)
            continue
        flush()
        current_lines.clear()
        level = len(match.group(1))
        title = match.group(2).strip()
        while stack and stack[-1][0] >= level:
            stack.pop()
        stack.append((level, title))
    flush()
    return sections or [([document.title], document.body)]


def normalize_whitespace(text: str) -> str:
    lines = [re.sub(r"\s+", " ", line).strip() for line in text.splitlines()]
    return "\n".join(line for line in lines if line)


def split_sentences(text: str) -> List[str]:
    normalized = normalize_whitespace(text)
    items = [part.strip() for part in SENTENCE_BOUNDARY.split(normalized) if part.strip()]
    result: List[str] = []
    for item in items:
        if len(item) <= MAX_CHARS:
            result.append(item)
            continue
        result.extend(hard_split(item, MAX_CHARS))
    return result


def hard_split(text: str, max_chars: int) -> List[str]:
    separators = ["，", "、", ",", " "]
    parts = [text]
    for separator in separators:
        expanded: List[str] = []
        for part in parts:
            if len(part) <= max_chars:
                expanded.append(part)
                continue
            fragments = part.split(separator)
            current = ""
            for fragment in fragments:
                candidate = fragment if not current else current + separator + fragment
                if current and len(candidate) > max_chars:
                    expanded.append(current)
                    current = fragment
                else:
                    current = candidate
            if current:
                expanded.append(current)
        parts = expanded
    final: List[str] = []
    for part in parts:
        if len(part) <= max_chars:
            final.append(part)
        else:
            final.extend(part[index:index + max_chars] for index in range(0, len(part), max_chars))
    return [part.strip() for part in final if part.strip()]


def overlap_tail(sentences: Sequence[str], max_chars: int) -> List[str]:
    selected: List[str] = []
    total = 0
    for sentence in reversed(sentences):
        if selected and total + len(sentence) > max_chars:
            break
        selected.append(sentence)
        total += len(sentence)
    return list(reversed(selected))


def pack_section(path: List[str], body: str) -> List[str]:
    sentences = split_sentences(body)
    if not sentences:
        return []
    prefix = " > ".join(path)
    chunks: List[str] = []
    current: List[str] = []
    current_length = 0
    for sentence in sentences:
        separator_size = 1 if current else 0
        if current and current_length + separator_size + len(sentence) > MAX_CHARS:
            chunks.append(f"【{prefix}】\n" + "\n".join(current))
            current = overlap_tail(current, OVERLAP_CHARS)
            current_length = sum(len(item) for item in current) + max(0, len(current) - 1)
        current.append(sentence)
        current_length += len(sentence) + (1 if len(current) > 1 else 0)
        # Prefer a natural sentence boundary after reaching target size.
        if current_length >= TARGET_CHARS and sentence.endswith(("。", "！", "？", "!", "?", "；", ";")):
            chunks.append(f"【{prefix}】\n" + "\n".join(current))
            current = overlap_tail(current, OVERLAP_CHARS)
            current_length = sum(len(item) for item in current) + max(0, len(current) - 1)
    if current:
        chunks.append(f"【{prefix}】\n" + "\n".join(current))
    return chunks


def build_chunks(documents: Sequence[KnowledgeDocument]) -> List[KnowledgeChunk]:
    chunks: List[KnowledgeChunk] = []
    for document in documents:
        source_index = 0
        for path, body in split_sections(document):
            section_path = " > ".join(path)
            parent_id = sha256_text(f"{document.source}|{section_path}")[:20]
            for text in pack_section(path, body):
                chunk_id = stable_int(f"{document.source}|{parent_id}|{source_index}|{text}")
                chunks.append(
                    KnowledgeChunk(
                        id=chunk_id,
                        source=document.source,
                        title=document.title,
                        domain=document.domain,
                        tags=document.tags,
                        section_path=section_path,
                        parent_id=parent_id,
                        chunk_index=source_index,
                        text=text,
                    )
                )
                source_index += 1
    if not chunks:
        raise RagConfigurationError("RAG 文档切分后没有可索引的 chunk")
    return chunks


def endpoint(base_url: str, suffix: str) -> str:
    normalized = base_url.rstrip("/")
    if normalized.endswith("/" + suffix):
        return normalized
    return normalized + "/" + suffix


def post_json(url: str, api_key: str, body: Dict[str, Any], timeout_seconds: float) -> Dict[str, Any]:
    request = urllib.request.Request(
        url,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        # Keep provider error short and never expose headers or credentials.
        detail = error.read().decode("utf-8", errors="replace")[:240]
        raise RagError(f"RAG 上游接口返回 HTTP {error.code}: {detail}") from error
    except urllib.error.URLError as error:
        raise RagError(f"RAG 上游接口不可达: {error.reason}") from error


class OpenAICompatibleEmbedder:
    def __init__(self, config: RagConfig) -> None:
        self.config = config

    def embed_texts(self, texts: Sequence[str]) -> List[List[float]]:
        if not self.config.embedding_api_key:
            raise RagConfigurationError("未配置 RAG_EMBEDDING_API_KEY 或 DEEPSEEK_API_KEY")
        if self.config.embedding_multimodal:
            # Ark's multimodal endpoint fuses a list of text/image inputs into
            # one embedding. A knowledge-base chunk needs one vector per text,
            # so issue one request per chunk instead of batching documents.
            return [self._multimodal_text_embedding(text) for text in texts]

        vectors: List[List[float]] = []
        for offset in range(0, len(texts), EMBED_BATCH_SIZE):
            batch = list(texts[offset:offset + EMBED_BATCH_SIZE])
            response = post_json(
                endpoint(self.config.embedding_base_url, "embeddings"),
                self.config.embedding_api_key,
                {
                    "model": self.config.embedding_model,
                    "input": batch,
                    "encoding_format": "float",
                },
                self.config.timeout_seconds,
            )
            data = response.get("data")
            if not isinstance(data, list) or len(data) != len(batch):
                raise RagError("Embedding 接口没有返回与输入数量一致的向量")
            ordered = sorted(data, key=lambda item: int(item.get("index", 0)))
            for item in ordered:
                vectors.append(self._normalize_vector(item.get("embedding") if isinstance(item, dict) else None))
        dimensions = {len(vector) for vector in vectors}
        if len(dimensions) != 1:
            raise RagError("Embedding 接口返回的向量维度不一致")
        return vectors

    def _multimodal_text_embedding(self, text: str) -> List[float]:
        response = post_json(
            endpoint(self.config.embedding_base_url, "embeddings/multimodal"),
            self.config.embedding_api_key,
            {
                "model": self.config.embedding_model,
                "input": [{"type": "text", "text": text}],
                "encoding_format": "float",
            },
            self.config.timeout_seconds,
        )
        data = response.get("data")
        if isinstance(data, dict):
            return self._normalize_vector(data.get("embedding"))
        if isinstance(data, list) and data:
            first = data[0] if isinstance(data[0], dict) else {}
            return self._normalize_vector(first.get("embedding"))
        raise RagError("多模态 Embedding 接口没有返回 data.embedding")

    def _normalize_vector(self, value: Any) -> List[float]:
        vector = value
        if isinstance(vector, list) and vector and isinstance(vector[0], list):
            vector = vector[0]
        if not isinstance(vector, list) or not vector:
            raise RagError("Embedding 接口返回了空向量")
        return [float(item) for item in vector]


class MilvusLiteStore:
    def __init__(self, config: RagConfig) -> None:
        self.config = config
        if not MILVUS_AVAILABLE:
            raise RagConfigurationError("项目虚拟环境未安装 pymilvus[milvus-lite]")
        config.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.client = MilvusClient(str(config.db_path))

    def is_indexed(self) -> bool:
        return bool(self.client.has_collection(self.config.collection_name))

    def rebuild(self, chunks: Sequence[KnowledgeChunk], vectors: Sequence[Sequence[float]]) -> Dict[str, Any]:
        if len(chunks) != len(vectors) or not vectors:
            raise RagError("chunk 数量与向量数量不匹配")
        dimension = len(vectors[0])
        if self.client.has_collection(self.config.collection_name):
            self.client.drop_collection(self.config.collection_name)
        self.client.create_collection(
            collection_name=self.config.collection_name,
            dimension=dimension,
            metric_type="COSINE",
        )
        entities = [chunk.entity(vector) for chunk, vector in zip(chunks, vectors, strict=True)]
        for offset in range(0, len(entities), EMBED_BATCH_SIZE):
            self.client.insert(
                collection_name=self.config.collection_name,
                data=entities[offset:offset + EMBED_BATCH_SIZE],
            )
        return {"chunks": len(entities), "dimension": dimension}

    def search(self, query_vector: Sequence[float], limit: int) -> List[Dict[str, Any]]:
        if not self.is_indexed():
            return []
        # Milvus Lite persists the collection to disk, but a newly opened
        # client sees it in released state until it is explicitly loaded.
        self.client.load_collection(self.config.collection_name)
        raw = self.client.search(
            collection_name=self.config.collection_name,
            data=[list(query_vector)],
            limit=limit,
            output_fields=[
                "text",
                "source",
                "title",
                "domain",
                "tags",
                "section_path",
                "parent_id",
                "chunk_index",
            ],
        )
        rows: List[Dict[str, Any]] = []
        for rank, hit in enumerate(raw[0] if raw else [], start=1):
            entity = hit.get("entity") if isinstance(hit, dict) else {}
            if not isinstance(entity, dict):
                continue
            rows.append(
                {
                    "id": hit.get("id"),
                    "denseScore": float(hit.get("distance", hit.get("score", 0.0))),
                    "denseRank": rank,
                    **entity,
                }
            )
        return rows

    def stats(self) -> Dict[str, Any]:
        if not self.is_indexed():
            return {"indexed": False, "chunks": 0}
        raw = self.client.get_collection_stats(self.config.collection_name)
        count = raw.get("row_count", raw.get("rowCount", 0)) if isinstance(raw, dict) else 0
        return {"indexed": True, "chunks": int(count or 0)}


def query_terms(text: str) -> set[str]:
    normalized = text.lower()
    terms = {match.group(0) for match in WORD.finditer(normalized)}
    cjk_chars = [match.group(0) for match in CJK.finditer(normalized)]
    for size in (2, 3, 4):
        terms.update("".join(cjk_chars[index:index + size]) for index in range(max(0, len(cjk_chars) - size + 1)))
    return {term for term in terms if len(term) >= 2}


def lexical_score(query: str, text: str) -> float:
    query_set = query_terms(query)
    if not query_set:
        return 0.0
    matched = len(query_set & query_terms(text))
    return matched / max(1, len(query_set))


def infer_domain(payload: Dict[str, Any]) -> str:
    mode = str(payload.get("mode") or "").strip().lower()
    if mode in {"recommendation", "beauty", "presale"}:
        return "presale"
    if mode in {"fulfillment", "mobility", "route"}:
        return "route"
    if mode in {"presale", "aftersale", "queue", "appointment", "route"}:
        return "appointment" if mode == "queue" else mode
    text = str(payload.get("message") or "")
    if any(token in text for token in ("退款", "投诉", "意见", "反馈", "翘边", "售后", "人工", "过敏")):
        return "aftersale"
    if any(token in text for token in ("预约", "改约", "排队", "等多久", "到店")):
        return "appointment"
    if any(token in text for token in ("怎么去", "路线", "导航", "出发地")):
        return "route"
    return "presale"


def retrieval_query(payload: Dict[str, Any]) -> str:
    """Enrich short follow-ups with persisted specialist working memory."""

    message = str(payload.get("message") or "").strip()
    context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
    session = context.get("agentSessionState") if isinstance(context.get("agentSessionState"), dict) else {}
    current_agent = str(session.get("currentAgent") or "")
    specialists = session.get("specialists") if isinstance(session.get("specialists"), dict) else {}
    current_state = specialists.get(current_agent) if isinstance(specialists.get(current_agent), dict) else {}
    working_memory = current_state.get("workingMemory") if isinstance(current_state.get("workingMemory"), dict) else {}

    parts = [message]
    if current_agent == "beauty_advisor_agent" or infer_domain(payload) == "presale":
        hand_profile = context.get("handProfile") if isinstance(context.get("handProfile"), dict) else working_memory.get("handProfile")
        if isinstance(hand_profile, dict) and hand_profile:
            profile_text = "、".join(f"{key}:{value}" for key, value in list(hand_profile.items())[:6])
            parts.append(f"手型画像 {profile_text}")
        style_name = context.get("styleName") or working_memory.get("styleName")
        if style_name:
            parts.append(f"当前款式 {str(style_name)[:80]}")
        reasons = context.get("scoreReasons") or working_memory.get("scoreReasons")
        if isinstance(reasons, list) and reasons:
            parts.append("试穿评分理由 " + "、".join(str(item)[:80] for item in reasons[:4]))
        previous_message = current_state.get("lastUserMessage")
        if previous_message and len(message) <= 18:
            parts.append(f"上轮问题 {str(previous_message)[:160]}")
    return "；".join(part for part in parts if part)[:1200]


def local_fusion(query: str, candidates: Sequence[Dict[str, Any]], expected_domain: str) -> List[Dict[str, Any]]:
    scored: List[Dict[str, Any]] = []
    for item in candidates:
        lexical = lexical_score(query, str(item.get("text") or ""))
        domain_bonus = 0.12 if item.get("domain") == expected_domain else 0.0
        dense_rank = int(item.get("denseRank") or 999)
        reciprocal_rank = 1.0 / (60 + dense_rank)
        score = 0.62 * float(item.get("denseScore") or 0.0) + 0.28 * lexical + 0.10 * domain_bonus + reciprocal_rank
        row = dict(item)
        row["lexicalScore"] = round(lexical, 4)
        row["fusionScore"] = round(score, 6)
        scored.append(row)
    return sorted(scored, key=lambda item: float(item["fusionScore"]), reverse=True)


def rerank_candidates(config: RagConfig, query: str, candidates: Sequence[Dict[str, Any]]) -> tuple[List[Dict[str, Any]], str]:
    if not candidates:
        return [], "empty"
    if not config.rerank_api_key:
        return list(candidates), "local_hybrid_fusion"
    documents = [str(item.get("text") or "")[:12000] for item in candidates]
    body = {
        "model": config.rerank_model,
        "query": query,
        "documents": documents,
        "top_n": min(FINAL_TOP_K, len(documents)),
        "instruct": "Given a customer-support question, retrieve passages that directly answer it without inventing policy.",
    }
    response, api_mode = call_rerank_api(config, body)
    results = response.get("results")
    if not isinstance(results, list) and isinstance(response.get("output"), dict):
        results = response["output"].get("results")
    if not isinstance(results, list):
        raise RagError("Rerank 接口没有返回 results")
    ordered: List[Dict[str, Any]] = []
    for item in results:
        if not isinstance(item, dict):
            continue
        index = item.get("index")
        if not isinstance(index, int) or index < 0 or index >= len(candidates):
            continue
        row = dict(candidates[index])
        row["rerankScore"] = float(item.get("relevance_score", 0.0))
        ordered.append(row)
    return ordered or list(candidates), "qwen3-rerank-" + api_mode


def call_rerank_api(config: RagConfig, body: Dict[str, Any]) -> tuple[Dict[str, Any], str]:
    compatible_url = endpoint(config.rerank_base_url, "reranks")
    native_body = {
        "model": body["model"],
        "input": {
            "query": body["query"],
            "documents": body["documents"],
        },
        "parameters": {
            "return_documents": True,
            "top_n": body["top_n"],
        },
    }
    style = config.rerank_api_style
    if style == "compatible":
        return post_json(compatible_url, config.rerank_api_key, body, config.timeout_seconds), "compatible"
    if style == "dashscope":
        return post_json(dashscope_rerank_url(config.rerank_base_url), config.rerank_api_key, native_body, config.timeout_seconds), "dashscope"

    if "dashscope.aliyuncs.com" in config.rerank_base_url and not any(
        marker in config.rerank_base_url for marker in ("/compatible-api/", "/compatible-mode/")
    ):
        return post_json(dashscope_rerank_url(config.rerank_base_url), config.rerank_api_key, native_body, config.timeout_seconds), "dashscope"

    try:
        return post_json(compatible_url, config.rerank_api_key, body, config.timeout_seconds), "compatible"
    except RagError as compatible_error:
        if "dashscope.aliyuncs.com" not in config.rerank_base_url or "HTTP 404" not in str(compatible_error):
            raise
        return post_json(dashscope_rerank_url(config.rerank_base_url), config.rerank_api_key, native_body, config.timeout_seconds), "dashscope"


def dashscope_rerank_url(base_url: str) -> str:
    if "dashscope.aliyuncs.com" in base_url:
        return "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"
    return endpoint(base_url, "services/rerank/text-rerank/text-rerank")


def diverse_top_k(candidates: Sequence[Dict[str, Any]], limit: int) -> List[Dict[str, Any]]:
    selected: List[Dict[str, Any]] = []
    seen_parents: set[str] = set()
    for candidate in candidates:
        parent_id = str(candidate.get("parent_id") or "")
        if parent_id and parent_id in seen_parents:
            continue
        selected.append(candidate)
        if parent_id:
            seen_parents.add(parent_id)
        if len(selected) >= limit:
            return selected
    for candidate in candidates:
        if candidate not in selected:
            selected.append(candidate)
        if len(selected) >= limit:
            break
    return selected


def context_text(hits: Sequence[Dict[str, Any]]) -> str:
    blocks: List[str] = []
    remaining = 2800
    for index, hit in enumerate(hits, start=1):
        source = str(hit.get("title") or hit.get("source") or "客服知识")
        section = str(hit.get("section_path") or "")
        text = str(hit.get("text") or "")
        block = f"[知识{index}｜{source}｜{section}]\n{text}"
        if len(block) > remaining:
            block = block[:remaining]
        if block.strip():
            blocks.append(block)
            remaining -= len(block)
        if remaining <= 0:
            break
    return "\n\n".join(blocks)


def source_hash(documents: Sequence[KnowledgeDocument]) -> str:
    fingerprint = "\n".join(document.source + "\n" + document.body for document in documents)
    return sha256_text(fingerprint)


def write_manifest(config: RagConfig, documents: Sequence[KnowledgeDocument], chunks: Sequence[KnowledgeChunk], dimension: int) -> None:
    config.manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest = {
        "builtAt": int(time.time()),
        "sourceHash": source_hash(documents),
        "embeddingModel": config.embedding_model,
        "dimension": dimension,
        "documents": len(documents),
        "syntheticRecords": sum(1 for document in documents if document.source.startswith("synthetic_beauty_case_")),
        "chunks": len(chunks),
        "chunking": {
            "strategy": "structure-aware-parent-child-recursive",
            "targetChars": TARGET_CHARS,
            "maxChars": MAX_CHARS,
            "overlapChars": OVERLAP_CHARS,
        },
    }
    config.manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")


def read_manifest(config: RagConfig) -> Dict[str, Any]:
    if not config.manifest_path.exists():
        return {}
    try:
        raw = json.loads(config.manifest_path.read_text(encoding="utf-8"))
        return raw if isinstance(raw, dict) else {}
    except Exception:
        return {}


def build_index(config: RagConfig | None = None, embedder: Embedder | None = None) -> Dict[str, Any]:
    config = config or RagConfig.from_env()
    if not config.enabled:
        return {"ok": False, "reason": "rag_disabled", **config.safe_status()}
    documents = load_documents(config.source_dir)
    chunks = build_chunks(documents)
    active_embedder = embedder or OpenAICompatibleEmbedder(config)
    vectors = active_embedder.embed_texts([chunk.text for chunk in chunks])
    store = MilvusLiteStore(config)
    write_result = store.rebuild(chunks, vectors)
    write_manifest(config, documents, chunks, write_result["dimension"])
    return {
        "ok": True,
        "mode": "offline_index",
        "documents": len(documents),
        "syntheticRecords": sum(1 for document in documents if document.source.startswith("synthetic_beauty_case_")),
        "chunks": write_result["chunks"],
        "dimension": write_result["dimension"],
        "manifest": str(config.manifest_path),
        "milvusUri": str(config.db_path),
        "strategy": "structure-aware-parent-child-recursive",
    }


def status(config: RagConfig | None = None) -> Dict[str, Any]:
    config = config or RagConfig.from_env()
    result = config.safe_status()
    result["milvusAvailable"] = MILVUS_AVAILABLE
    documents = load_documents(config.source_dir)
    chunks = build_chunks(documents)
    result.update({
        "sourceDocuments": len(documents),
        "sourceChunks": len(chunks),
        "syntheticRecords": sum(1 for document in documents if document.source.startswith("synthetic_beauty_case_")),
        "currentSourceHash": source_hash(documents),
    })
    if not MILVUS_AVAILABLE:
        result.update({"indexed": False, "chunks": 0, "manifest": {}, "indexStale": True})
        return result
    try:
        store = MilvusLiteStore(config)
        result.update(store.stats())
    except Exception as exc:
        result.update({"indexed": False, "chunks": 0, "error": str(exc)})
    manifest = read_manifest(config)
    result["manifest"] = manifest
    result["indexStale"] = str(manifest.get("sourceHash") or "") != result["currentSourceHash"]
    return result


def preview_chunks(config: RagConfig | None = None, limit: int = 30) -> Dict[str, Any]:
    config = config or RagConfig.from_env()
    documents = load_documents(config.source_dir)
    chunks = build_chunks(documents)
    return {
        "ok": True,
        "mode": "offline_preview",
        "documents": len(documents),
        "syntheticRecords": sum(1 for document in documents if document.source.startswith("synthetic_beauty_case_")),
        "chunks": len(chunks),
        "strategy": "structure-aware-parent-child-recursive",
        "items": [
            {
                "id": chunk.id,
                "source": chunk.source,
                "title": chunk.title,
                "domain": chunk.domain,
                "tags": chunk.tags,
                "sectionPath": chunk.section_path,
                "parentId": chunk.parent_id,
                "chunkIndex": chunk.chunk_index,
                "chars": len(chunk.text),
                "text": chunk.text,
            }
            for chunk in chunks[:max(1, limit)]
        ],
    }


def retrieve_for_chat(payload: Dict[str, Any], config: RagConfig | None = None, embedder: Embedder | None = None) -> Dict[str, Any]:
    config = config or RagConfig.from_env()
    query = retrieval_query(payload)
    if not config.enabled:
        return {"available": False, "reason": "rag_disabled", "contextText": "", "hits": []}
    if not query:
        return {"available": False, "reason": "empty_query", "contextText": "", "hits": []}
    if not config.embedding_api_key and embedder is None:
        return {"available": False, "reason": "embedding_key_missing", "contextText": "", "hits": []}
    try:
        store = MilvusLiteStore(config)
        if not store.is_indexed():
            return {"available": False, "reason": "knowledge_not_indexed", "contextText": "", "hits": []}
        active_embedder = embedder or OpenAICompatibleEmbedder(config)
        query_vector = active_embedder.embed_texts([query])[0]
        expected_domain = infer_domain(payload)
        candidates = store.search(query_vector, INITIAL_TOP_K)
        fused = local_fusion(query, candidates, expected_domain)
        try:
            reranked, rerank_mode = rerank_candidates(config, query, fused[:RERANK_CANDIDATES])
        except RagError:
            # Rerank improves precision but must never make an otherwise valid
            # dense retrieval unavailable to the customer-facing agent.
            reranked, rerank_mode = fused[:RERANK_CANDIDATES], "local_hybrid_fusion_rerank_fallback"
        final_hits = diverse_top_k(reranked, FINAL_TOP_K)
        safe_hits = [
            {
                "source": hit.get("source"),
                "title": hit.get("title"),
                "domain": hit.get("domain"),
                "sectionPath": hit.get("section_path"),
                "score": round(float(hit.get("rerankScore", hit.get("fusionScore", 0.0))), 4),
                "text": hit.get("text"),
            }
            for hit in final_hits
        ]
        return {
            "available": bool(final_hits),
            "reason": "ok" if final_hits else "no_match",
            "expectedDomain": expected_domain,
            "retrievalMode": rerank_mode,
            "contextText": context_text(final_hits),
            "hits": safe_hits,
        }
    except Exception as exc:
        return {
            "available": False,
            "reason": "retrieval_failed",
            "detail": str(exc)[:300],
            "contextText": "",
            "hits": [],
        }


def write_json(payload: Dict[str, Any]) -> None:
    sys.stdout.buffer.write(json.dumps(payload, ensure_ascii=False).encode("utf-8"))


def main() -> None:
    try:
        payload = json.loads(sys.stdin.buffer.read().decode("utf-8-sig") or "{}")
        if not isinstance(payload, dict):
            raise RagConfigurationError("RAG 请求必须是 JSON 对象")
        action = str(payload.get("action") or "status")
        if action == "status":
            write_json({"ok": True, **status()})
            return
        if action == "preview_chunks":
            write_json(preview_chunks(limit=int(payload.get("limit") or 30)))
            return
        if action == "build":
            write_json(build_index())
            return
        if action == "search":
            write_json(retrieve_for_chat(payload))
            return
        raise RagConfigurationError(f"不支持的 RAG action：{action}")
    except Exception as exc:
        write_json({"ok": False, "error": str(exc)})


if __name__ == "__main__":
    main()
