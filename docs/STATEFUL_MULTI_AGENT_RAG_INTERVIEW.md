# NailGlow 状态化多 Agent、路线履约与 Beauty RAG 面试资料

## 1. 项目改造的一句话定位

NailGlow 不再把美甲顾问、预约、售后和路线规划当成一次性工具调用，而是通过 Supervisor 把会话所有权切换给状态化的专业 Agent。每个 Agent 都保留自己的工作记忆、最近对话、RAG 证据和业务中间结果；用户话题变化时，Supervisor 再自动切换 Agent。

## 2. 为什么不再使用“客服调一次专家工具”

一次性工具链有四个问题：

1. 专家处理结束后立即回到客服，用户无法继续追问。
2. 美甲顾问已经读取过手型和试穿结果，下一轮却可能再次追问。
3. 不同领域的中间结果混在通用会话中，状态边界不清晰。
4. 前端看到的只是一串工具执行记录，不是真正的会话负责人切换。

改造后，专业 Agent 是会话参与者，工具只是 Agent 完成业务动作时的副作用边界。

## 3. 真实运行结构

```text
用户输入
  ↓
实时业务上下文装配
  ├─ 当前预约
  ├─ 门店空位
  ├─ 手型画像与试穿评分
  ├─ 上架款式候选
  └─ 上一轮 agentSessionState
  ↓
RAG 检索
  ↓
Supervisor 两级路由
  ├─ 确定性规则命中 → 直接交给专业 Agent
  └─ 规则未命中 → 客服 Agent 语义判断 → 二次委派
  ↓
状态化专业 Agent
  ├─ 美甲顾问 Agent
  ├─ 预约 Agent
  ├─ 售后 Agent
  └─ 门店履约 Agent
  ↓
结构化回复 / toolCalls
  ↓
Java 工具执行层
  ↓
MySQL 业务真实状态 + 会话 Agent 状态持久化
```

## 4. 两级自动路由

关键代码：`src/main/python/specialist_agents.py` 和 `src/main/python/agent_graph.py`。

第一级是确定性路由：

```python
if mode in {"route", "fulfillment", "mobility"} or any(
    token in text
    for token in ("路线", "导航", "怎么走", "怎么去", "去哪", "哪家店")
):
    return "fulfillment_agent"
```

这一层处理高频、高确定性的语义，目标是低延迟、可解释和现场演示稳定。

第二级是客服 Agent 语义兜底：

```python
result = specialist_agents.run("customer_agent", payload)
semantic_target = specialist_from_customer_result(result)

if semantic_target != "customer_agent":
    session = agent_session_state.activate(
        session,
        semantic_target,
        payload,
        count_turn=False,
    )
```

如果用户使用了没有被关键词覆盖的表达，客服 Agent 可以输出 `route/presale/appointment/aftersale` 等 intent，Graph 再进行第二次委派。

设计收益：

- 规则命中时不浪费一次 LLM 分类。
- 规则未覆盖的自然表达仍能语义路由。
- 业务确认、取消和 `pendingAction` 依然由硬规则保护。

## 5. 每个 Agent 的独立状态

关键代码：`src/main/python/agent_session_state.py`。

```python
{
    "currentAgent": "beauty_advisor_agent",
    "previousAgent": "customer_agent",
    "turn": 5,
    "handoffCount": 2,
    "specialists": {
        "beauty_advisor_agent": {
            "status": "COMPLETED",
            "turnCount": 3,
            "workingMemory": {
                "handProfile": {},
                "scoreMetrics": {},
                "styleCandidates": [],
                "recommendedStyles": []
            },
            "recentTurns": [],
            "ragEvidence": []
        }
    }
}
```

状态分成三类：

1. `workingMemory`：当前专业任务需要反复使用的结构化数据。
2. `recentTurns`：当前专业 Agent 最近六轮对话摘要。
3. `ragEvidence`：该 Agent 最近使用的知识库来源和切片。

为什么不给每个 Agent 复制一份预约真实数据：

- 会产生 A 店和 B 店状态不一致。
- Agent 工作状态可以分区，但业务事实必须以 MySQL 为唯一真实源。
- 预约成功与否不能由 Agent 的自然语言回复决定。

## 6. 话题变化时为什么能自动回到客服

前端不再把上一轮 `supportMode` 强行带给下一轮自由输入。每条自由输入都使用 `mode=general` 重新进入 Supervisor。

与当前专业 Agent 有关的表达，例如“还有别的款吗”“可以补一下吗”，会读取 `currentAgent` 和领域追问词继续留在当前 Agent。完全无关的问题会回到客服 Agent，不需要“返回客服”按钮。

## 7. 美甲顾问如何联合手型数据与 RAG

前端会把当前试穿结果中的以下数据传给后端：

- `handProfile`：修长、纤细、掌宽、软润等量化画像。
- `scoreMetrics`：手型适配、肤色协调、风格匹配、场景实用性等。
- `scoreReasons`：可解释评分理由。
- `styleCandidates`：数据库当前上架的真实款式。

检索时不只对当前一句问话做 Embedding。对于“还有别的吗”这种短追问，会拼接当前 Beauty Agent 的手型画像、当前款式、评分理由和上轮问题，再生成查询向量。

```python
if current_agent == "beauty_advisor_agent":
    parts.append(f"手型画像 {profile_text}")
    parts.append(f"当前款式 {style_name}")
    parts.append(f"上轮问题 {previous_message}")
```

这解决了短追问缺少语义、向量召回不稳定的问题。

## 8. Beauty RAG 数据和建库链路

知识源包含八组人工编写文档，以及 128 条合成推荐案例。

128 条数据来自：

```text
4 类手型
× 4 类肤色
× 4 类使用场景
× 2 类风格偏好
= 128 条合成案例
```

数据矩阵：`src/main/resources/rag/customer_service/synthetic_beauty_case_matrix.json`。

建库过程：

```text
Markdown + 合成案例矩阵
  ↓
生成 128 条独立 KnowledgeDocument
  ↓
按 Markdown 标题保留 sectionPath
  ↓
按句子边界切片
  ↓
目标 460 字 / 最大 620 字 / 80 字 overlap
  ↓
Embedding
  ↓
Milvus Lite COSINE 向量索引
  ↓
Dense Recall Top12
  ↓
向量分 + 词法分 + 领域加分 + RRF 融合
  ↓
qwen3-rerank 可选重排
  ↓
去重后 Top4 证据进入 Agent Context
```

所有合成记录都带有“Demo 合成数据”标记，不应在面试中说成真实用户数据。

## 9. 门店履约 Agent 为什么不是普通导航工具

普通导航已经知道起点和终点。门店履约需要先决定去哪家店，再决定什么时候出发和选什么交通方式。

硬约束：

- 门店有可用时段。
- 用户能在服务开始前到店。
- 服务结束时间不超过用户 deadline。
- 门店支持当前款式。
- 恶劣天气时不推荐不安全的骑行或步行方案。

软排序：

```python
score = (
    (100 if feasible else 0)
    + deadline_buffer_bonus
    - travel_minutes * 0.65
    - travel_cost * 0.12
    + parking_bonus
    + style_support_bonus
    - weather_penalty
)
```

硬约束不能被软分数补偿。一个无法在截止时间前做完的方案，不能因为便宜或近就被推荐。

## 10. 真实天气和 Demo 降级

路线 Agent 优先使用高德 Web 服务 Key 查询门店 adcode 对应的实况天气，标记 `weatherSource=amap_live`。接口不可用时，显式降级为 `demo_fallback`，不伪装成真实数据。

天气不只用来展示，还会影响：

- 通勤安全缓冲时间。
- 出行方式的风险罚分。
- 恶劣天气下步行、骑行的可行性。
- 最终建议出发时间。

前端会展示“出发 → 到店 → 服务完成”时间轴、天气来源、天气建议、停车提示和总成本。

## 11. 定位授权为什么放在前端明确处理

用户在对话中输入“允许”不等于浏览器真的授权了定位。如果把“允许”发给 LLM，模型可能误以为已获得权限。

改造后：

1. 已知路线快捷动作会在用户点击时请求 `navigator.geolocation`。
2. 规则未命中的路线请求如果缺少起点，显示“授权定位并继续”状态卡。
3. 用户输入“允许定位”时，前端拦截该意图，真正调用浏览器权限 API，不再作为普通聊天发给模型。
4. 拒绝、超时、定位不可用和非安全上下文都有不同错误提示。

用户直接说“我在南昌西站”时，后端也会从自然语言中提取出发地，不强制要求精确定位。

## 12. 模型和工具的安全边界

每个 Agent 拥有工具白名单：

- 美甲顾问 Agent：无写工具。
- 预约 Agent：只能提出创建或改约。
- 售后 Agent：只能更新客服事项或请求人工。
- 履约 Agent：完成约束规划后才能提出预约。

LLM 只返回结构化 `toolCalls`。Java 工具执行层再校验用户、当前预约、门店和动作，最终写入 MySQL。

## 13. 可直接背诵的 90 秒版本

我这次多 Agent 改造的重点，是把专业 Agent 从一次性工具链升级成有独立状态的会话参与者。Supervisor 首先通过确定性规则处理高频意图；如果规则没有命中，客服 Agent 会做语义判断，然后进行第二次委派。

每个专业 Agent 都有自己的 workingMemory、recentTurns 和 ragEvidence。例如美甲顾问会持续保留用户的手型量化画像、试穿评分、候选款式和最近使用的 RAG 证据。用户说“还有别的吗”时，系统会继续在美甲顾问状态中追问；用户换成普通话题时，又会自动回到客服。

Beauty RAG 中我加入了八组人工知识和 128 条合成推荐案例。案例是由手型、肤色、场景和风格偏好做笛卡尔组合生成的，经过结构化切片、Embedding、Milvus Lite 向量召回、本地融合和可选 Rerank 后进入 Agent 上下文。

路线部分不是普通导航，而是门店履约。它将门店空位、服务时长、用户 deadline、真实高德路线、实况天气、停车和成本一起做硬约束过滤和软排序。模型只提出动作，真正的预约仍由 Java 后端校验并写入数据库。

## 14. 高频追问和答法

### 为什么不全部使用 LLM Supervisor？

高频且边界清晰的意图用硬规则更稳定、更低成本。LLM 只作为规则未命中时的语义兜底，所以是混合路由，不是纯关键词，也不是全 LLM。

### 这些 Agent 是否真的有状态？

是。每个 Agent 都有命名空间状态，保存工作记忆、最近对话、RAG 证据和专业结果。状态会跟随客服消息 metadata 写入 MySQL，下一轮从最新助手消息恢复。

### 为什么不让每个 Agent 直接修改数据库？

Agent 是概率系统，不能成为业务授权边界。Agent 只输出结构化动作，Java 负责身份、参数、单有效预约规则和数据库写入。

### 128 条数据是真实用户数据吗？

不是。这是 Demo 合成知识，目的是验证切片、Embedding、检索、Rerank 和个性化回答链路。真实上线需要由美甲师审核知识，并使用真实用户反馈评估。

### 如何防止合成数据污染回答？

合成记录有明确来源和 Demo 标记，实时价格、预约、门店和用户试穿数据的优先级高于 RAG。RAG 只提供推荐与解释证据，不能覆盖实时业务事实。

### 天气 API 失败怎么办？

返回 `weatherSource=demo_fallback`，明确告诉前端是 Demo 降级；不会把 Mock 天气说成实况数据。最终时间仍会保留基础通勤缓冲。

## 15. 演示前需要做的一次性操作

新增知识源后，旧 Milvus 向量库不会自动伪装成最新。管理后台会显示“当前向量索引待重建”，需要使用“重建知识库”执行切片、Embedding 和 Milvus 索引替换。

这一步会调用已配置的 Embedding API，因此应在演示前完成，不要在现场等待建库。
