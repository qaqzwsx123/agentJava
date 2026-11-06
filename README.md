# agentJava — 企业知识库智能问答系统

面向企业员工和知识库管理员的资料问答项目，处理制度、流程和项目说明分散、人工查找繁琐的问题。管理员上传后，系统解析、分块并建立索引；用户提问时，由 Agent 按需调用知识库工具，结合资料生成回答，通过 SSE 返回文本与工具状态。

本仓库由 **qaqzwsx123** 基于 [nageoffer/ragent](https://github.com/nageoffer/ragent) 学习和改进，重点是本地模型接入、知识库问答和异常处理。原有架构和功能不表述为本人独立开发，目前以本地搭建和验证为主，没有企业上线效果数据。

主要技术栈及用途：

- **后端与管理：** Java、Spring Boot、MyBatis-Plus、Sa-Token，处理接口、认证、持久化和管理业务，具体版本以 pom.xml 为准。
- **模型接入：** Ollama 本地聊天、百炼云端嵌入和重排；infra-ai 统一模型适配、档位和故障切换，主 Agent 模型由 AgentScope 配置组装。
- **文档处理：** Markdown、Tika 等解析器提取结构，结合结构感知分块、长度预算、局部重叠和草稿打包。
- **检索排序：** pgvector 或 Milvus 向量后端，以及 Elasticsearch 关键词、LightRAG 图谱、You.com 联网通道；去重、RRF、重排与证据门控按配置执行。
- **智能体：** AgentScope Java、ReActAgent、Toolkit 和状态存储，完成工具判断、执行、结果回写和继续决策。
- **异步交互：** RocketMQ 提交分块任务，Redis 支持幂等和并发控制，Reactor 订阅事件并通过 SSE 推送。
- **界面追踪：** React、TypeScript、Vite 提供管理和聊天页面；Trace、Langfuse 和检索评测帮助定位问题。

## 处理流程

**入库：** 创建知识库 → 上传或获取文件 → 保存文档记录 → MQ 分块任务 → 解析 Block → 分块与打包 → 计算向量 → 写入分块及索引 → 更新状态。

**问答：** 用户提问 → Agent 决定是否调用工具 → 改写和意图识别 → 确定范围 → 已启用通道并行召回 → 去重与 RRF → 重排和证据检查 → 工具结果回写 → Agent 回答 → SSE 推送与状态保存。

Agent 也可直接回答普通问题或多次调用工具。知识库文档的 Pipeline 分支当前提示重构中；演示使用直接分块，独立 ingestion 流水线不代表该分支已经可用。

## 功能拆解记录

参照 [ShortLink-Platform 的 README](https://github.com/qaqzwsx123/ShortLink-Platform)，将当前功能及既有修复拆成 **2026 年 6 月 1 日至 10 月 1 日，共 123 个每日条目**。

**日期是重新编排的回顾时间轴，不是经核实的当天实际开发日期，也不表示每天新增了对应代码。** 已有功能以梳理和拆解表述；真实修复关联原提交，Git 作者和时间保留原记录。

### 2026 年 6 月

- 6月1日：整理企业知识库问答场景，区分员工和管理员。
- 6月2日：划分 framework、infra-ai、system、rag、agent 等模块。
- 6月3日：整理 Maven 依赖和 bootstrap 启动入口。
- 6月4日：梳理 Result 与 Results 的统一响应封装。
- 6月5日：梳理全局异常处理，区分业务错误和远端异常。
- 6月6日：梳理 ChatQuestion 参数校验和空问题限制。
- 6月7日：梳理 Sa-Token 登录、退出和登录态校验。
- 6月8日：梳理用户上下文拦截器的设置与清理。
- 6月9日：整理 LoginUser、UserContext 与异步信息传递。
- 6月10日：梳理用户管理和示例问题接口。
- 6月11日：梳理 MyBatis-Plus 配置与公共字段填充。
- 6月12日：梳理 Redis 缓存键序列化。
- 6月13日：梳理 Redis Lua 分配 Snowflake 节点编号。
- 6月14日：梳理自定义主键生成器与 Hutool ID。
- 6月15日：梳理重复提交注解、切面与幂等键。
- 6月16日：梳理 MQ 消费幂等状态和重复消息处理。
- 6月17日：梳理统一消息生产者与 RocketMQ 适配器。
- 6月18日：梳理事务消息、本地事务和状态回查。
- 6月19日：梳理 SSE 发送工具与流式任务管理。
- 6月20日：梳理任务取消标记和后台资源清理。
- 6月21日：梳理模型配置，区分能力、档位和超时。
- 6月22日：梳理 OpenAI 兼容聊天请求模板。
- 6月23日：梳理 Ollama 本地聊天适配器。
- 6月24日：梳理百炼等云端聊天适配器。
- 6月25日：梳理 RoutingLLMService 的候选选择和故障切换。
- 6月26日：梳理模型健康状态和流式首包探测。
- 6月27日：梳理单条和批量 EmbeddingService 接口。
- 6月28日：梳理 RerankService 输入、输出及分数。
- 6月29日：梳理视觉模型和 Token 预算估算。
- 6月30日：汇总基础组件、业务入口与模型设施的职责。

### 2026 年 7 月

- 7月1日：拆分知识库创建、查询、修改和删除。
- 7月2日：梳理知识库模型、逻辑集合与向量维度。
- 7月3日：梳理 VectorTargetResolver 的模型与落点解析。
- 7月4日：梳理本地上传，区分文件存储和文档记录。
- 7月5日：梳理 RemoteFileFetcher 的 URL 文件获取。
- 7月6日：梳理文档分页、预览、下载和配置修改。
- 7月7日：梳理文档启停、删除及索引清理。
- 7月8日：梳理直接分块入口与事务 MQ 任务提交。
- 7月9日：梳理消费者的操作者恢复和 finally 清理。
- 7月10日：梳理日志与 RUNNING、SUCCESS、FAILED 状态。
- 7月11日：梳理 DefaultIngestionKernel 固定入库编排。
- 7月12日：梳理 MIME 识别、解析档位与 ParserRegistry。
- 7月13日：梳理 Markdown 标题、段落、表格和代码解析。
- 7月14日：梳理 Tika 通用文档解析。
- 7月15日：梳理 CSV 等表格的列名和行数据解析。
- 7月16日：梳理图片资产、描述和来源位置。
- 7月17日：区分 Block 结构单元和 Chunk 检索单元。
- 7月18日：梳理 ChunkingService 的整篇和结构分块。
- 7月19日：梳理按 Block 实际类型选择分块器。
- 7月20日：梳理 HeadingHandler 的章节路径维护。
- 7月21日：梳理 ParagraphChunker 完整段落保留。
- 7月22日：梳理 TextSplitter 长度预算、边界和重叠。
- 7月23日：梳理 TableChunker 完整行切分和表头关系。
- 7月24日：梳理 CodeChunker 按代码行拆分。
- 7月25日：梳理列表分块及图片描述、链接保留。
- 7月26日：梳理 ChunkDraft 和 ChunkPacker 草稿打包。
- 7月27日：梳理 ChunkAssembler 的 ID、序号和章节补充。
- 7月28日：区分 content 展示正文与 embeddingText 输入。
- 7月29日：梳理嵌入结果条数和维度校验。
- 7月30日：梳理关系库和向量索引按文档替换。
- 7月31日：梳理单块编辑、重新分块和 URL 定时刷新。

### 2026 年 8 月

- 8月1日：梳理 RetrievalEngine 子问题检索入口。
- 8月2日：梳理查询词映射与业务术语转换。
- 8月3日：梳理复杂问题改写与拆分。
- 8月4日：梳理改写失败后的归一化原问题回退。
- 8月5日：整理人名、编号、金额丢失风险和验证方法。
- 8月6日：梳理意图树、知识库意图和集合绑定。
- 8月7日：梳理无有效意图或低置信度时走全局。
- 8月8日：梳理失效知识库绑定过滤。
- 8月9日：梳理 ScopeQuota 主路和补充路名额。
- 8月10日：梳理已启用搜索通道并行执行。
- 8月11日：梳理单通道超时和异常空结果。
- 8月12日：梳理向量通道定向与全局范围。
- 8月13日：梳理 pgvector 余弦距离和相似度转换。
- 8月14日：梳理 Milvus 后端和条件装配。
- 8月15日：梳理 Elasticsearch 匹配与 BM25 分数。
- 8月16日：梳理 LightRAG 图谱证据获取。
- 8月17日：梳理图谱来源归属和返回顺序赋分。
- 8月18日：梳理 You.com 标题、摘录和来源组织。
- 8月19日：梳理联网开关、密钥与失败降级。
- 8月20日：梳理候选身份键和去重边界。
- 8月21日：梳理 RRF 按名次和通道权重累加。
- 8月22日：梳理 rrfK 与原始分数尺度差异。
- 8月23日：梳理 candidateLimit 对候选池的限制。
- 8月24日：梳理问题、候选正文和 contextTopK 的重排调用。
- 8月25日：梳理重排模型和供应商客户端路由。
- 8月26日：梳理百炼 relevance_score 与 rerankScore。
- 8月27日：梳理本批最高重排分的证据门控。
- 8月28日：梳理文档来源和元数据补充。
- 8月29日：梳理检索上下文、引用和答案合成衔接。
- 8月30日：梳理只返回检索证据的 EvalController。
- 8月31日：整理通道归因及融合、重排证据存活日志。

### 2026 年 9 月

- 9月1日：区分 Agent 工具决策和 Workflow 固定编排。
- 9月2日：梳理引擎配置及条件注解装配。
- 9月3日：梳理 AgentEngineConfiguration 的 Ollama 接入。
- 9月4日：梳理 ReActAgent 模型、提示词和状态组装。
- 9月5日：梳理 AgentToolCatalog 和 Toolkit 注册。
- 9月6日：梳理 KnowledgeSearchTool 参数校验。
- 9月7日：梳理 KnowledgeSearchFacade 的检索、合成组织。
- 9月8日：梳理 AgentChatController 创建 SseEmitter。
- 9月9日：梳理用户、会话、taskId 和运行名额。
- 9月10日：梳理 RunScope、RuntimeContext 上下文传递。
- 9月11日：梳理 launchStream、streamEvents 和 subscribe。
- 9月12日：梳理 AgentStreamEventBridge 事件转换。
- 9月13日：梳理工具结果回写和继续决策。
- 9月14日：梳理 PostgreSQL 消息和 Agent 状态存取。
- 9月15日：梳理最近消息、摘要和历史预算。
- 9月16日：梳理 Redis 并发控制和名额释放。
- 9月17日：梳理 Disposable.dispose、取消标记和清理。
- 9月18日：梳理正常、异常、取消和超时收尾。
- 9月19日：梳理 Trace、Langfuse 与阶段耗时。
- 9月20日：梳理 Dashboard 的条件实现。
- 9月21日：梳理独立 ingestion 节点流水线和任务。
- 9月22日：记录知识库 Pipeline 重构中，演示用直接分块。
- 9月23日：梳理本地聊天与云端嵌入、重排部署分工。
- 9月24日：梳理嵌入模型和 PostgreSQL 1024 维配置。
- 9月25日：回顾分块完成但列表 running 的轮询修复。
- 9月26日：回顾 LightRAG 405，改用 POST /documents/paginated。
- 9月27日：回顾已召回资料但后续聊天合成失败的定位。
- 9月28日：回顾 Ollama 思考参数和 FAST 30 秒超时调整。
- 9月29日：回顾异常、空答案资料回退并保留取消。
- 9月30日：整理参数映射、异常、空答案和取消回归记录。

### 2026 年 10 月

- 10月1日：汇总 123 天拆解，串联入库、检索、Agent 回答与异常处理。

## 问题排查与处理记录

以下日期对应上面的回顾编排；实际修改时间以链接中的原提交为准，不把编排日期当成开发事实。

### 9月25日回顾：分块完成，列表仍显示 running

**问题：** 后端输出分块与向量写入完成日志，页面仍显示旧状态。

**分析：** 异步任务提交成功不代表处理结束，页面若不继续读取状态，可能停留在启动时的 running。

**处理：** 已有修复对运行中的文档约每 2 秒刷新一次，结束后停止。实际运行前端在独立工程，页面修改需同步到实际启动的前端目录。

**真实提交：** [4b15033（2026-10-18）](https://github.com/qaqzwsx123/agentJava/commit/4b15033)。

### 9月26日回顾：LightRAG 查询出现 405

**问题：** 图谱请求返回 405，但向量写入仍有成功日志。

**分析：** 请求方法与远端接口不匹配，不能仅延长超时，也不能当成嵌入模型失败。

**处理：** 查询旧图谱文档改用 POST /documents/paginated。图谱属于外部服务，关系库事务不能保证所有外部索引原子成功。

**真实提交：** [4b15033（2026-10-18）](https://github.com/qaqzwsx123/agentJava/commit/4b15033)。

**代码：** [LightRagClient](rag/src/main/java/com/nageoffer/ai/ragent/rag/core/graph/LightRagClient.java)。

### 9月27日至29日回顾：已经召回资料，工具仍返回检索失败

**问题：** 向量和图谱已召回，重排也有结果，但页面提示知识库检索异常。

**分析：** 原排查记录表明，改写的本地聊天调用超时并影响健康状态；工具末尾再次合成答案时没有可用候选，失败发生在资料检索之后。

**处理：** Ollama 映射 reasoning_effort，思考为 medium、非思考为 none；FAST 辅助超时从 120 秒改为 30 秒；合成遇到 RemoteException 或空答案时交回已检索资料，同时保留取消语义。改写失败回退归一化问题是已有机制，不算此次新增。

**验证记录：** 原提交记录三组测试共 24 项通过，并记录单独改写约 19 秒、合成约 14 秒。这是当时的本地验证，不是本次重新运行结果，也不是完整 Agent 链路性能保证。

**真实提交：** [241aeb5（2026-10-19）](https://github.com/qaqzwsx123/agentJava/commit/241aeb5)。

**代码：** [OllamaChatClient](infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/OllamaChatClient.java)、[KnowledgeSearchFacade](rag/src/main/java/com/nageoffer/ai/ragent/rag/service/KnowledgeSearchFacade.java)。

## 模块入口

| 模块 | 职责 |
|---|---|
| framework | 响应、异常、上下文、ID、MQ、幂等、取消和 SSE |
| infra-ai | 聊天、嵌入、重排、视觉模型适配与路由 |
| system | 登录、用户、审计和示例问题 |
| rag/core | 解析、分块和固定入库内核 |
| rag/knowledge | 资料管理、MQ 消费和定时刷新 |
| rag/ingestion | 独立节点流水线和任务管理 |
| rag/rag | 改写、意图、检索、排序和 Workflow 问答 |
| agent | AgentScope、工具、会话状态和事件桥接 |
| bootstrap | 应用启动和配置 |
| mcp-server | 独立工具服务 |

## 运行和验证

- 启动前准备实际启用的数据库、Redis、RocketMQ、文件存储和模型服务，材料见 [resources](resources/) 与 [数据库说明](resources/database/README.md)。
- 当前本地演示配置使用 Ollama qwen3.5:2b 聊天、百炼 qwen3.7-text-embedding-flash 嵌入和 qwen3.7-text-rerank 重排；实际模型、维度和开关以部署配置及供应商可用性为准，不在 README 写入密钥。
- 上传后触发直接分块，等待 SUCCESS，再核对证据和答案。新版上传不自动覆盖同名旧文档，需要处理旧版及其索引。
- 开启 ragent.eval.enabled=true 后，可用 GET /rag/eval?question=... 观察检索证据和耗时；不生成最终答案，不能代替完整 Agent 评测。
- 分块默认目标 1024 字符、超长切分重叠 128 字符，允许结构容忍空间；更换嵌入模型需验证查询与文档向量兼容。
- 通道按配置启用，不把已实现当成每次实际参与；无答案、实体丢失和图谱残留需单独验证。

## 来源和许可证

基于 [nageoffer/ragent](https://github.com/nageoffer/ragent) 学习和改进，采用 Apache-2.0，详见 [LICENSE](LICENSE)，保留原代码版权声明。

[原 README 存档](docs/upstream-readme.md) 保留原说明，存档内相对链接按原仓库根目录解析。
