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

本节与重建后的 123 次提交逐日对应。**日期是人为编排的回顾日期，不是当天实际开发时间。** 初始整仓导入按职责拆分，后续重放已有差异，不改变上游版权和贡献来源。

[Git 历史重建说明](HISTORY_RECONSTRUCTION.md) · [每日文件与来源映射](docs/reconstructed-history.json)

### 2026 年 6 月

- 6月1日：回溯导入：工程构建与模块配置（.gitattributes、.gitignore等）。
- 6月2日：回溯导入：公共框架（snowflake_init）。
- 6月3日：回溯导入：公共框架-cache（RedisKeySerializer）。
- 6月4日：回溯导入：公共框架-cancellation（TaskCancellation、TaskCancellationTest）。
- 6月5日：回溯导入：公共框架-config（DataBaseConfiguration、RocketMQAutoConfiguration等）。
- 6月6日：回溯导入：公共框架-context（ApplicationContextHolder、LoginUser等）。
- 6月7日：回溯导入：公共框架-convention（ChatMessage、ChatRequest等）。
- 6月8日：回溯导入：公共框架-database（JsonbTypeHandler、MyMetaObjectHandler）。
- 6月9日：回溯导入：公共框架-distributedid（CustomIdentifierGenerator、SnowflakeIdInitializer）。
- 6月10日：回溯导入：公共框架-errorcode（BaseErrorCode、IErrorCode）。
- 6月11日：回溯导入：公共框架-exception（AbstractException、ClientException等）。
- 6月12日：回溯导入：公共框架-idempotent（IdempotentConsume、IdempotentConsumeAspect等）。
- 6月13日：回溯导入：公共框架-mq（MessageWrapper、DelegatingTransactionListener等）。
- 6月14日：回溯导入：公共框架-trace（RagStreamTraceSupport、RagTraceContext等）。
- 6月15日：回溯导入：公共框架-validation（ChatQuestion、ChatQuestionTest）。
- 6月16日：回溯导入：公共框架-web（GlobalExceptionHandler、Results等）。
- 6月17日：回溯导入：认证与系统管理（BizChangeLogController、BizChangeLogService等）。
- 6月18日：回溯导入：认证与系统管理（SampleQuestionService、AuthController等）。
- 6月19日：回溯导入：认证与系统管理（AuthService、UserService等）。
- 6月20日：回溯导入：模型接入-chat（LLMService、AIHubMixChatClient等）。
- 6月21日：回溯导入：模型接入-chat（RoutingLLMService、ProbeStreamBridge等）。
- 6月22日：回溯导入：模型接入-config（AIModelProperties）。
- 6月23日：回溯导入：模型接入-embedding（EmbeddingService、RoutingEmbeddingService等）。
- 6月24日：回溯导入：模型接入-enums（ModelCapability、ModelProvider等）。
- 6月25日：回溯导入：模型接入-http（HttpMediaTypes、HttpResponseHelper等）。
- 6月26日：回溯导入：模型接入-model（ChatTierConfigValidator、ModelCaller等）。
- 6月27日：回溯导入：模型接入-rerank（RerankService、RoutingRerankService等）。
- 6月28日：回溯导入：模型接入-token（HeuristicTokenCounterService、TokenCounterService）。
- 6月29日：回溯导入：模型接入-util（LLMResponseCleaner、LogSafe）。
- 6月30日：回溯导入：模型接入-vlm（RoutingVlmService、VlmService）。

### 2026 年 7 月

- 7月1日：回溯导入：文档解析（BlockTextRenderer、CsvDocumentParser等）。
- 7月2日：回溯导入：文档解析（MinerUPollingExecutor、MinerUProperties等）。
- 7月3日：回溯导入：结构感知分块（ChunkingService、BlockAwareChunkerDispatcher等）。
- 7月4日：回溯导入：结构感知分块（ListChunker、ParagraphChunker等）。
- 7月5日：回溯导入：固定入库内核（ChunkEmbeddingService、DefaultIngestionKernel等）。
- 7月6日：回溯导入：知识库与文档管理（KnowledgeBaseController、KnowledgeChunkController等）。
- 7月7日：回溯导入：知识库与文档管理（KnowledgeChunkDO、KnowledgeDocumentChunkLogDO等）。
- 7月8日：回溯导入：知识库与文档管理（KnowledgeBaseService、KnowledgeChunkService等）。
- 7月9日：回溯导入：知识库与文档管理（员工培训、招聘信息等）。
- 7月10日：回溯导入：知识库与文档管理（退换货条件、退换货流程与申请方式等）。
- 7月11日：回溯导入：知识库与文档管理（MAC-MINI、MAC-STUDIO等）。
- 7月12日：回溯导入：可配置节点流水线（IngestionPipelineController、IngestionTaskController等）。
- 7月13日：回溯导入：可配置节点流水线（StructuredDocument、ChunkEnrichType等）。
- 7月14日：回溯导入：可配置节点流水线（IngestionPipelineService、IngestionTaskService等）。
- 7月15日：回溯导入：RAG能力-graph（GraphQueryService、GraphEvidence等）。
- 7月16日：回溯导入：RAG能力-guidance（IntentGuidanceService、AmbiguityLLMChecker等）。
- 7月17日：回溯导入：RAG能力-intent（DefaultIntentClassifier、IntentClassifier等）。
- 7月18日：回溯导入：RAG能力-keyword（EsKeywordIndexService、EsKeywordRetrieverService等）。
- 7月19日：回溯导入：RAG能力-memory（ConversationMemoryService、ConversationMemorySummaryService等）。
- 7月20日：回溯导入：RAG能力-prompt（RAGPromptService、AgentPromptCacheManager等）。
- 7月21日：回溯导入：RAG能力-rewrite（MultiQuestionRewriteService、QueryRewriteService等）。
- 7月22日：回溯导入：RAG能力-skill（AgentSkill、AgentSkillCacheManager等）。
- 7月23日：回溯导入：RAG能力-source（CitationContextEnricher、CitationMarkup等）。
- 7月24日：回溯导入：RAG能力-storage（ObjectStorageClient、OssObjectStorageClient等）。
- 7月25日：回溯导入：RAG能力-vector（MilvusVectorRetrieverService、MilvusVectorStoreService等）。
- 7月26日：回溯导入：RAG能力-vector（VectorStoreService、GraphSyncingVectorStoreService等）。
- 7月27日：回溯导入：多通道召回与后处理（KnowledgeRetrievalResult、MultiChannelRetrievalEngine等）。
- 7月28日：回溯导入：多通道召回与后处理（ChannelAttribution、DeduplicationPostProcessor等）。
- 7月29日：回溯导入：Workflow接口与管理（DashboardController、DashboardService等）。
- 7月30日：回溯导入：Workflow接口与管理（AgentProfileController、AgentSkillController等）。
- 7月31日：回溯导入：Workflow接口与管理（AgentSkillPageRequest、AgentSkillSaveRequest等）。

### 2026 年 8 月

- 8月1日：回溯导入：Workflow接口与管理（ConversationDO、ConversationMessageDO等）。
- 8月2日：回溯导入：Workflow接口与管理（EvalController、AgentProfileAdminService等）。
- 8月3日：回溯导入：Workflow接口与管理（DefaultFileStorageService、ConversationMessageServiceImpl等）。
- 8月4日：回溯导入：Workflow接口与管理（RagentCoreApplicationTests、TestRagentApplication等）。
- 8月5日：回溯导入：智能体-admin（AgentDashboardService、AgentDashboardPerformance等）。
- 8月6日：回溯导入：智能体-config（AgentEngineConfiguration、AgentProperties等）。
- 8月7日：回溯导入：智能体-confirm（AgentConfirmDenialMiddleware）。
- 8月8日：回溯导入：智能体-controller（AgentChatController、AgentConversationController等）。
- 8月9日：回溯导入：智能体-dao（AgentContextCompactionDO、AgentConversationDO等）。
- 8月10日：回溯导入：智能体-dto（AgentBlock、AgentCompletionPayload等）。
- 8月11日：回溯导入：智能体-enums（AgentMemoryExtractionStatus、AgentMemorySourceType等）。
- 8月12日：回溯导入：智能体-memory（AgentContextChars、AgentContextCompactionMiddleware等）。
- 8月13日：回溯导入：智能体-memory（AgentMemoryDecision、AgentMemoryItem等）。
- 8月14日：回溯导入：智能体-service（AgentChatService、AgentConversationService等）。
- 8月15日：回溯导入：智能体-skill（AgentSkillMaskingMiddleware、SkillLoadTool等）。
- 8月16日：回溯导入：智能体-state（PgAgentStateStore）。
- 8月17日：回溯导入：智能体-tool（AgentMcpClients、AgentMcpMeta等）。
- 8月18日：回溯导入：智能体-trace（AgentErrorTypes、AgentRunTracer等）。
- 8月19日：回溯导入：智能体-trace（RagentOtelTracingMiddleware、AgentRunTracerTest等）。
- 8月20日：回溯导入：MCP工具服务（McpServerApplication、McpServerConfig等）。
- 8月21日：回溯导入：MCP工具服务（AfterSaleMapper、CartMapper等）。
- 8月22日：回溯导入：MCP工具服务（BitAfterSaleQueryMcpExecutor、BitCartQueryMcpExecutor等）。
- 8月23日：回溯导入：MCP工具服务（AssetMcpExecutor、AssetRenewalMcpExecutor等）。
- 8月24日：回溯导入：前端-组件与工程（.env、.eslintrc等）。
- 8月25日：回溯导入：前端-组件与工程（App、RelativeTime等）。
- 8月26日：回溯导入：前端-组件与工程（ThinkingIndicator、ThumbIcons等）。
- 8月27日：回溯导入：前端-组件与工程（textarea、tooltip等）。
- 8月28日：回溯导入：前端-组件与工程（sessionService、settingsService等）。
- 8月29日：回溯导入：前端-页面（AgentChatPage、ChangeLogsPage等）。
- 8月30日：回溯导入：前端-页面（WorkflowDashboard、IntentEditPage等）。
- 8月31日：回溯导入：应用启动与部署配置（RagentApplication、application）。

### 2026 年 9 月

- 9月1日：回溯导入：数据库与部署资源（README、init_data等）。
- 9月2日：回溯导入：数据库与部署资源（AgentProfileInitMain、AgentSkillInitMain等）。
- 9月3日：回溯导入：数据库与部署资源（110-support-presale、111-support-presale-recommend等）。
- 9月4日：回溯导入：数据库与部署资源（ApplicationYamlConfig、InitializationActions等）。
- 9月5日：回溯导入：数据库与部署资源（SimpleJson、AgentSkillInitMain等）。
- 9月6日：回溯导入：数据库与部署资源（AgentChatClient、AgentMemoryMechanismMain等）。
- 9月7日：回溯导入：文档与展示资源（admin-datasets、admin-knowledge-base等）。
- 9月8日：回溯导入：文档与展示资源（multi-channel-retrieval、qa-answer等）。
- 9月9日：回溯导入：文档与展示资源（ragent-framework-v2、ragent-framework等）。
- 9月10日：回溯重放：本地 Ollama 模型接入配置。
- 9月11日：回溯重放：业务代码学习注释。
- 9月12日：回溯重放：Controller 和 ServiceImpl 调用说明。
- 9月13日：回溯重放：Controller 调用链及异步回调注释。
- 9月14日：回溯重放：业务理解注释。
- 9月15日：回溯重放：Agent 会话记忆方法与字段说明。
- 9月16日：回溯重放：工具调用和 Trace 注释。
- 9月17日：回溯重放：framework 方法注释。
- 9月18日：回溯重放：infra-ai 模型客户端方法注释。
- 9月19日：回溯重放：MCP 方法和 Mapper SQL 说明。
- 9月20日：回溯重放：rag 解析、入库、检索与聊天注释。
- 9月21日：回溯重放：Langfuse 链路追踪配置。
- 9月22日：回溯重放：LightRAG 图谱测试配置。
- 9月23日：回溯重放：百炼模型与 1024 维向量配置。
- 9月24日：回溯重放：文档轮询、LightRAG 405 与 MQ 超时修复。
- 9月25日：回溯重放：framework 类职责说明。
- 9月26日：回溯重放：framework 剩余类职责说明。
- 9月27日：回溯重放：infra-ai 类型职责说明。
- 9月28日：回溯重放：Ollama 思考参数、辅助超时与资料回退修复。
- 9月29日：回溯重放：看板管理类职责说明。
- 9月30日：回溯重放：ingestion、knowledge、rag 类职责说明。

### 2026 年 10 月

- 10月1日：回溯重放：项目 README、每日拆解和原说明存档。

## 问题排查与处理记录

以下日期对应上面的回顾编排；实际修改时间以备份分支中的原提交为准，不把编排日期当成开发事实。

### 9月24日回顾：分块完成，列表仍显示 running

**问题：** 后端输出分块与向量写入完成日志，页面仍显示旧状态。

**分析：** 异步任务提交成功不代表处理结束，页面若不继续读取状态，可能停留在启动时的 running。

**处理：** 已有修复对运行中的文档约每 2 秒刷新一次，结束后停止。实际运行前端在独立工程，页面修改需同步到实际启动的前端目录。

**真实提交：** [4b15033（2026-10-18）](https://github.com/qaqzwsx123/agentJava/commit/4b15033)。

### 9月24日回顾：LightRAG 查询出现 405

**问题：** 图谱请求返回 405，但向量写入仍有成功日志。

**分析：** 请求方法与远端接口不匹配，不能仅延长超时，也不能当成嵌入模型失败。

**处理：** 查询旧图谱文档改用 POST /documents/paginated。图谱属于外部服务，关系库事务不能保证所有外部索引原子成功。

**真实提交：** [4b15033（2026-10-18）](https://github.com/qaqzwsx123/agentJava/commit/4b15033)。

**代码：** [LightRagClient](rag/src/main/java/com/nageoffer/ai/ragent/rag/core/graph/LightRagClient.java)。

### 9月28日回顾：已经召回资料，工具仍返回检索失败

**问题：** 向量和图谱已召回，重排也有结果，但页面提示知识库检索异常。

**分析：** 原排查记录表明，改写的本地聊天调用超时并影响健康状态；工具末尾再次合成答案时没有可用候选，失败发生在资料检索之后。

**处理：** Ollama 映射 reasoning_effort，思考为 medium、非思考为 none；FAST 辅助超时从 120 秒改为 30 秒；合成遇到 RemoteException 或空答案时交回已检索资料，同时保留取消语义。改写失败回退归一化问题是已有机制，不算此次新增。

**验证记录：** 原提交记录三组测试共 24 项通过，并记录单独改写约 19 秒、合成约 14 秒。这是当时的本地验证，不是本次重新运行结果，也不是完整 Agent 链路性能保证。

**真实提交：** [241aeb5（2026-10-19）](https://github.com/qaqzwsx123/agentJava/commit/241aeb5)。

**代码：** [OllamaChatClient](infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/OllamaChatClient.java)、[KnowledgeSearchFacade](rag/src/main/java/com/nageoffer/ai/ragent/rag/service/KnowledgeSearchFacade.java)。


### 补充排查项：以下依据现有代码整理

以下 15 项记录典型问题的表现、原因和现有处理机制，**不是新增的实际故障经历，也不表示本次修改了业务代码或重新跑过相关测试**。尚未完全覆盖的边界在条目中说明，不另编造发生日期或修复提交。

#### 01 更换嵌入模型后，分块任务提示向量维度不符

**问题：** 模型接口能返回向量，但任务在写入前报告嵌入维度与部署向量空间不匹配。

**分析：** 模型输出维度、VectorTarget 声明维度和数据库向量列需要一致；仅修改模型名不会自动调整物理空间。维度相同也不保证不同模型的语义坐标兼容。

**处理：** 现有 ChunkEmbeddingService 校验返回条数、空向量和每条维度，避免把错误推迟到数据库转换阶段。排查时核对模型、目标维度和建表配置；替换不兼容模型需要重建文档向量并保持查询侧兼容，不应截断或补零冒充正确向量。

**代码：** [ChunkEmbeddingService](rag/src/main/java/com/nageoffer/ai/ragent/core/ingest/embed/ChunkEmbeddingService.java)。

#### 02 编辑分块后，页面正文与检索结果不一致

**问题：** 管理员修改了分块正文，但相似度检索仍可能按旧文本匹配。

**分析：** 只更新 content 而不更新 embeddingText 和向量，会让展示内容、重建输入和索引不一致。

**处理：** KnowledgeChunkServiceImpl.update 现有实现同时更新正文、哈希、统计和 embeddingText，再重新计算向量并调用 updateChunk。排查时逐项核对这三份数据；该路径不修改原始文件，后续重新分块会覆盖手工内容，也不保证完整图谱随单块编辑同步。

**代码：** [KnowledgeChunkServiceImpl](rag/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeChunkServiceImpl.java)。

#### 03 长段落拆开后又被合并，块中出现重复文本

**问题：** 超长段落带重叠切成多片，若随后重新拼接，重叠内容可能在一个块里重复，切分预算也可能失效。

**分析：** 切分产物和普通短草稿不能完全按同一种方式打包，表格按行数拆开的片也不宜被随意并回。

**处理：** 现有 ChunkDraft.pieces 给真正拆开的草稿设置 piece 标记，ChunkPacker 在打包时识别分片边界。核对长段落分片、重叠区域和最终块内容，而不是简单要求每块严格等于 1024 字符。

**代码：** [ChunkDraft](rag/src/main/java/com/nageoffer/ai/ragent/core/chunk/model/ChunkDraft.java)、[ChunkPacker](rag/src/main/java/com/nageoffer/ai/ragent/core/chunk/blockaware/ChunkPacker.java)。

#### 04 表格能展示，检索却难以对应列名和值

**问题：** 原始表格有姓名和职责，切成纯单元格文本后，检索难以区分谁对应哪项职责。

**分析：** 表格视觉位置表达的字段关系，直接拼接值后可能丢失；按字符硬切也可能把一行拆散。

**处理：** 现有 TableChunker 按完整数据行和行数、字符预算分组，content 保留 Markdown 表格，向量正文生成“列名: 值”。装配阶段再补充缺少的章节路径。单行超长时不会在中间硬切，因此仍需检查实际嵌入模型输入限制。

**代码：** [TableChunker](rag/src/main/java/com/nageoffer/ai/ragent/core/chunk/blockaware/TableChunker.java)、[ChunkAssembler](rag/src/main/java/com/nageoffer/ai/ragent/core/chunk/model/ChunkAssembler.java)。

#### 05 意图识别选错库，目标资料始终进不了候选

**问题：** 问题指向某项制度，但意图绑定到其他知识库，定向检索只能找到表面相近资料。

**分析：** 意图分数是范围选择信号，不是绝对正确结论；高置信度也可能选错，低置信度强行定向则容易漏掉目标库。

**处理：** 现有 RetrievalScopeResolver 在无有效意图、低置信度或绑定库全部失效时走全局；定向时 ScopeQuota 可从原通道预算中保留其他有效库的补充名额。补充名额只保证通道出口候选数量，不保证通过融合和重排，也不等于用户权限校验。

**代码：** [RetrievalScopeResolver](rag/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/channel/RetrievalScopeResolver.java)、[ScopeQuota](rag/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/channel/ScopeQuota.java)。

#### 06 同一个意图被重复查询，融合贡献被放大

**问题：** 多个子问题或候选结果重复指向同一意图节点，可能造成同一库重复扇出、相同片段重复占名次。

**分析：** 若按每条 NodeScore 直接查询，重复节点不仅增加开销，还可能让 RRF 对同一路证据重复累加贡献。

**处理：** 现有 RetrievalScopeResolver.extractKbIntents 按意图节点 ID 去重，同一节点只保留最高分，并要求达到最低分且绑定有效集合。排查时检查意图节点、绑定库及通道原始候选是否重复，不能只看最后列表已去重。

**代码：** [RetrievalScopeResolver](rag/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/channel/RetrievalScopeResolver.java)。

#### 07 删除知识库后，图谱里仍残留旧资料

**问题：** 向量或关系库已清理，图谱服务却可能仍返回旧库证据。

**分析：** 外部图谱删除是尽力同步，不参与关系库的同一事务；意图绑定也可能保留已删除集合名。

**处理：** 现有范围解析先将绑定集合与有效知识库求交；图谱结果再按 file_path 识别集合归属，全局模式不保留无主证据并记录警告。该处理不能自动修复图谱本身的残留，定向补充和文档级删除还需单独核对，不能表述成完整权限隔离。

**代码：** [RetrievalScopeResolver](rag/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/channel/RetrievalScopeResolver.java)、[GraphSearchChannel](rag/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/channel/GraphSearchChannel.java)。

#### 08 图谱接口成功，但系统显示图谱没有证据

**问题：** LightRAG 返回了 response 文本，却没有可用于归属判断的 references，最终候选为空。

**分析：** 当前按库筛选依赖 references 中的 file_path；一段无来源的整体 response 无法可靠确定属于哪个知识库。

**处理：** LightRagClient 在集合过滤生效时丢弃无法归属的 response 兜底块并告警。排查远端版本、include_references 支持和 file_path 编码，不应为了让界面有结果就直接绕过来源检查。

**代码：** [LightRagClient](rag/src/main/java/com/nageoffer/ai/ragent/rag/core/graph/LightRagClient.java)。

#### 09 增加召回通道后，有用资料反而被挤掉

**问题：** 图谱或联网召回数量增加，但最终答案遗漏原先能回答的资料。

**分析：** 多通道存在有限的融合池和上下文名额；新通道的噪声可能占据靠前排名，重排也不能保证纠正所有错误。

**处理：** 现有 FusionPostProcessor 通过通道权重、rrfK 和 candidateLimit 控制融合与候选池；RerankPostProcessor 记录通道输入输出及图谱存活率。排查时沿原始召回、融合池、重排输出对比同一证据，再决定调权、扩池或关闭通道，不直接把不同尺度原始分数相加。

**代码：** [FusionPostProcessor](rag/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/postprocessor/FusionPostProcessor.java)、[RerankPostProcessor](rag/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/postprocessor/RerankPostProcessor.java)。

#### 10 候选数量少于 Top-K，也不能跳过重排

**问题：** 只有两条候选、希望保留五条，看似无需截断，但答案仍可能优先使用不相关片段，证据门控也可能没有分数可读。

**分析：** 重排除了截断，还负责根据问题和候选正文调整顺序并给相关性分数；候选少不意味着候选正确。

**处理：** BaiLianRerankClient 在候选非空且 topN 为正时仍调用模型，实际请求数量取 topN 与候选数较小值；读取 relevance_score 并保留 rerankScore。核对模型请求及输出，而不是只验证返回条数。

**代码：** [BaiLianRerankClient](infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/rerank/BaiLianRerankClient.java)。

#### 11 明明设置了门槛，不相关资料仍被放行

**问题：** 配置最低重排分后，仍可能看到无精排分的候选进入后续上下文。

**分析：** 证据门控依赖有效 rerankScore；当前逻辑按本批最高分判断，不逐条过滤。缺少分数的降级路径与低分路径不是一回事。

**处理：** 现有 EvidenceGatePostProcessor 在正数阈值启用时，最高有效分低于阈值才整批丢弃；没有可读分数则警告并放行。启动校验还会拒绝正数门槛与显式关闭重排的冲突配置。排查需同时检查重排调用、返回分数和门控日志，不能承诺任何异常都严格拒答。

**代码：** [EvidenceGatePostProcessor](rag/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/postprocessor/EvidenceGatePostProcessor.java)。

#### 12 一个外部通道超时，不能把所有召回都当失败

**问题：** 联网或图谱请求变慢、鉴权失败或返回异常，但向量通道可能已有有效资料。

**分析：** 若并行任务的异常直接传播到总流程，辅助通道故障会覆盖已取得的候选；日志中的空结果也可能是超时降级而不是库里无资料。

**处理：** MultiChannelRetrievalEngine 为已启用通道独立异步执行并施加超时，异常转换为该通道空结果，再汇总其他通道。排查时区分关闭、无命中和异常降级，查看每路耗时与错误日志；返回空结果不代表后台 HTTP 工作一定已立即停止。

**代码：** [MultiChannelRetrievalEngine](rag/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/MultiChannelRetrievalEngine.java)。

#### 13 用户点击停止，不能被资料回退逻辑吞掉

**问题：** 用户取消模型生成后，如果把所有远端异常都转成检索资料，主 Agent 可能继续生成。

**分析：** 用户主动取消和模型服务失败语义不同；普通异常兜底若覆盖取消，会破坏停止操作。

**处理：** KnowledgeSearchFacade 在捕获 RemoteException 后先用 TaskCancellation 判断取消，是取消则继续传播，普通合成异常才回退资料。现有 KnowledgeSearchFacadeTest 包含相关取消与异常用例；本次仅记录代码逻辑，没有重新执行测试或宣称所有取消路径均已验证。

**代码：** [KnowledgeSearchFacade](rag/src/main/java/com/nageoffer/ai/ragent/rag/service/KnowledgeSearchFacade.java)、[TaskCancellation](framework/src/main/java/com/nageoffer/ai/ragent/framework/cancellation/TaskCancellation.java)。

#### 14 MQ 消费线程复用，操作者可能丢失或串到下一条消息

**问题：** HTTP 上传者和异步消费线程不同，审计字段可能没有操作者；线程复用又可能带入上一次处理的用户信息。

**分析：** 请求线程上下文不会随消息跨线程、跨进程自动传递，设置后不清理会污染后续任务。

**处理：** KnowledgeDocumentChunkConsumer 从事件 operator 恢复 LoginUser.username，并在 finally 中清理 UserContext。这里恢复的是审计用户名，不是完整登录身份或授权信息，不能把它当成消费者已获得用户的全部访问权限。

**代码：** [KnowledgeDocumentChunkConsumer](rag/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkConsumer.java)。

#### 15 定时刷新与手动分块同时执行，文件引用可能提前切换

**问题：** 远程文件更新时管理员又点击分块，可能发生同文档重复处理；新文件若提前替换旧地址，后续失败也更难恢复。

**分析：** 调度任务的锁只解决部分并发，仍需与手动入口竞争文档运行状态，并按阶段处理文件和状态切换。

**处理：** ScheduleRefreshProcessor 使用租约心跳、阶段检查和 tryMarkRunning 竞争运行资格；无变化则跳过，先存新文件供本轮处理，确认分块 SUCCESS 后才切换正式文件元数据，并按阶段清理资源。外部索引仍非统一原子事务，丢锁与失败恢复要结合日志和对应测试核对。

**代码：** [ScheduleRefreshProcessor](rag/src/main/java/com/nageoffer/ai/ragent/knowledge/schedule/ScheduleRefreshProcessor.java)、[ScheduleLockManager](rag/src/main/java/com/nageoffer/ai/ragent/knowledge/schedule/ScheduleLockManager.java)。

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
