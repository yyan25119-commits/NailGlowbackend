# NailGlow RAG 与 Milvus Lite

## 目标

为智能客服提供可追溯的售前、预约、售后、投诉和路线知识，不把这些知识硬编码在提示词中。

## 文件地图

| 模式 | 主要文件 | 职责 |
| --- | --- | --- |
| 离线知识源 | `src/main/resources/rag/customer_service/*.md` | 可审阅、可替换的客服话术与规则 |
| 离线切分与索引 | `src/main/python/rag_knowledge_base.py` | Markdown 解析、分层切分、Embedding、Milvus Lite 写入 |
| 离线触发 | `RagKnowledgeBaseService.java`、`AdminController.java` | 查看状态、预览切片、手动重建索引 |
| 在线检索 | `agent_graph.py` 的 `retrieve_knowledge_node` | 在 Customer Agent 前检索相关知识 |
| 在线生成 | `customer_service_agent.py` | 接收 `ragContext`，使用证据生成客服回答 |

## 离线模式

离线模式不是“离线模型”。它表示不在用户聊天请求中做文档解析和批量向量化，而是由管理员手动触发：

1. 加载 Markdown 知识源。
2. 按标题、FAQ、段落和句子边界切分。
3. 调用 Embedding API 批量生成向量。
4. 将向量、文本和元数据写入 `runtime/rag/nailglow_customer_knowledge.db` 指定的本地 Milvus Lite 存储路径。当前安装的 Milvus Lite 版本会将该路径实现为包含 collection 数据的本地目录，而不需要独立 Milvus 服务进程。
5. 保存 manifest，记录语料哈希、模型名、维度和切分配置。

## 在线模式

在线模式发生在每次客服消息进入 LangGraph 后：

1. 根据用户消息和当前客服 mode 做领域推断。
2. 调用 Embedding API 向量化 query。
3. 在 Milvus Lite 做 dense Top-K 召回。
4. 使用关键词覆盖率进行轻量混合重排。
5. 如配置了 `RAG_RERANK_API_KEY`，调用 qwen3-rerank 做二次排序。
6. 只将最终少量证据拼成 `ragContext` 注入 Customer Agent。

## 切分策略

当前策略是“结构感知的父子切分”，而不是固定长度硬切：

- 先以 Markdown H1/H2/H3 建立章节路径。
- FAQ 标题和回答保持在同一父章节内。
- 段落按中文句号、问号、感叹号、分号等自然边界打包。
- 目标区间约 360 到 520 个中文字符，最大 620 字符。
- 相邻切片保留约 80 字符重叠，减少答案跨边界断裂。
- 每个 chunk 保存 `source/title/domain/tags/section_path/parent_id/chunk_index`。
- 在线阶段按 domain 过滤、去除同一父章节的重复命中，再调用 rerank。

## 配置

代码不会读取或写入明文 Key。后端进程只从环境变量读取：

```text
RAG_ENABLED=true
DEEPSEEK_API_KEY=...                  # Embedding Key，已有环境变量
RAG_EMBEDDING_BASE_URL=https://ark.cn-beijing.volces.com/api/v3
RAG_EMBEDDING_MODEL=doubao-embedding-vision-251215
RAG_EMBEDDING_MULTIMODAL=true
RAG_RERANK_API_KEY=...                # 请使用已旋转的新 Key
RAG_RERANK_BASE_URL=https://dashscope.aliyuncs.com/compatible-api/v1
RAG_RERANK_MODEL=qwen3-rerank
RAG_RERANK_API_STYLE=auto
```

不要把 Key 写入 Markdown、`application.properties`、Git 或聊天记录。
