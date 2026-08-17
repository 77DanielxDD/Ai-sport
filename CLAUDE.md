# CLAUDE.md

用中文回复。
本文件只放 AI 写代码实时约束。项目背景、接口、部署、验收说明见 `README.md`、`docs/system_design.md`、`docs/api_reference.md`、`docs/deployment_guide.md`。

规则冲突时：P0 > P1 > P2 > P3。

## 项目目标

本项目是 AI Sport 智能运动视频分析系统。当前目标是在不做本地模型训练的条件下，用工程约束提升视频动作分析链路的真实性、异步任务稳定性、RAG 检索可评测性、报告结构一致性和生产可交付性。

核心方向：真实视频上传与异步分析、MediaPipe/OpenCV 动作评估、Spring Boot 任务状态闭环、Agent + RAG 问答、pytrec_eval 离线检索评测、失败不造假、生产配置可验证。

执行 RAG、Agent、动作分析、任务队列、部署相关改动前，必须先显式读取相关源码和文档。

## P0 硬性红线

违反任一条即任务失败：

- 禁止编造视频分析结果、动作次数、角度、评分、关键帧、报告图片、训练建议或任务状态。
- 禁止在 fallback 中生成假分析、假图片路径、假 RAG 来源、假 chunk_id、假 Agent 工具调用结果。
- 禁止进行模型训练，包括 LoRA、SFT、DPO、本地微调、训练脚本、训练数据管线。
- 禁止写入真实 API Key、`.env` 密钥、token、账号凭证、云存储密钥或数据库密码。
- 禁止修改 `frontend/node_modules/`、`target/`、`logs/`、`uploaded-videos/`、`chroma_db/`、`__pycache__/`、虚拟环境、构建产物和依赖缓存。
- 禁止回滚用户未明确要求回滚的改动。
- 禁止一次性完整重写大型核心文件，尤其是 `src/main/java/com/example/aisport/service/VideoService.java`、`ai-service/app/main.py`、`frontend/src/App.jsx`。
- 禁止未确认就执行大规模重构、跨层迁移、删除文件、初始化 Git、强制 push 或破坏性 Git 操作。
- 禁止把 RAGAS、pytrec_eval、LLM 裁判、业务规则评测混为一套指标。检索评测必须基于 qrels/run 和稳定 `chunk_id`。
- 禁止把前端展示状态当作后端真实状态来源。任务状态以数据库和后端 API 为准。

## P1 AI 编码流程

非小修任务必须遵守：

1. 先执行 `git pull --ff-only origin main` 同步最新代码。
2. 先读相关文件，确认现有实现、调用链、数据结构和测试。
3. 先输出简短实施计划，等待用户确认后再编码。
4. 分阶段增量开发，每阶段只完成一个目标。
5. 每阶段完成后输出改动清单、受影响文件、验证结果或阻断原因。
6. 修改共享模型、API 协议、任务状态、RAG 结果结构、评测数据格式时，同步后端、AI 服务、前端、测试和文档。
7. 遇到已有未提交改动，必须判断是否与本任务相关；不得擅自覆盖或回滚。

小修可直接改：明显空引用、导入错误、拼写错误、单行配置、文档措辞、非行为性注释。

## P2 架构规范

重点文件：

- `src/main/java/com/example/aisport/controller/VideoController.java`：视频上传、状态、报告、比较 API
- `src/main/java/com/example/aisport/service/VideoService.java`：视频保存、异步派发、Python 分析、结果落库
- `src/main/java/com/example/aisport/service/mq/VideoAnalysisProducer.java`：分析任务入队
- `src/main/java/com/example/aisport/service/mq/VideoAnalysisConsumer.java`：分析任务消费
- `src/main/java/com/example/aisport/task/AnalysisTaskService.java`：任务状态流转
- `src/main/java/com/example/aisport/entity/ExerciseVideo.java`：视频实体与分析结果字段
- `src/main/java/com/example/aisport/config/SecurityConfig.java`：认证授权边界
- `src/main/java/com/example/aisport/rag/`：Java 侧 RAG 检索与知识注入
- `src/main/resources/application*.properties`：后端环境配置
- `ai-service/app/main.py`：FastAPI、MediaPipe/OpenCV 动作分析
- `ai-service/app/rag/ingest.py`：知识库直读、清洗、切块、元数据生成
- `ai-service/app/rag/vector_store.py`：Chroma 向量库
- `ai-service/app/rag/bm25_store.py`：BM25 稀疏检索
- `ai-service/app/rag/retriever.py`：混合检索与 rerank
- `ai-service/app/rag/evaluation.py`：pytrec_eval 离线检索评测
- `ai-service/scripts/eval_runner.py`：离线评测命令入口
- `ai-service/app/agent/`：Python Agent 编排
- `frontend/src/api.js`：前端 API 客户端
- `frontend/src/store.js`：前端状态缓存
- `frontend/src/pages/UploadPage.jsx`：上传页
- `frontend/src/pages/TaskPage.jsx`：任务轮询和进度页
- `frontend/src/pages/ReportPage.jsx`：分析报告页
- `frontend/src/pages/QaPage.jsx`：智能问答页
- `frontend/src/pages/AdminPage.jsx`：管理后台

新增模块优先放置：

```text
src/main/java/com/example/aisport/
src/test/java/com/example/aisport/
ai-service/app/
ai-service/scripts/
frontend/src/
docs/
```

分层要求：

- React 页面只负责交互和展示，不承载动作评分、任务状态流转、候选校验、RAG 评测等核心业务逻辑。
- Prompt 只负责表达任务，不承载可由代码稳定计算的评分、预算、状态、权限、检索指标。
- 视频状态、任务状态、取消/重试、失败原因必须由后端代码计算或校验。
- AI 服务只返回真实分析出的结构化结果；无法分析时返回明确错误，不补假数据。
- RAG 检索结果必须透出稳定 `chunk_id`，用于 qrels/run 对齐。
- pytrec_eval 只做离线检索评测，不负责线上召回、答案生成或 LLM 质量裁判。
- 管理端接口必须经过后端鉴权，不得只靠前端隐藏入口。

标准环境变量：

```env
SPRING_PROFILES_ACTIVE=dev
SERVER_PORT=8080
DEV_DB_URL=jdbc:mysql://localhost:3306/ai_sport
DEV_DB_USERNAME=root
DEV_DB_PASSWORD=changeme
DEV_RABBIT_HOST=localhost
DEV_RABBIT_PORT=5673
AI_SERVICE_BASE_URL=http://127.0.0.1:8000
AI_PYTHON_ANALYZE_PATH=/analyze
AI_ANALYSIS_TARGET_FPS=15
AI_ANALYSIS_MAX_WIDTH=960
AI_MAX_VIDEO_DURATION_SECONDS=35
AI_MEDIA_BASE_DIR=./uploaded-videos/output
KNOWLEDGE_PATH=
CHROMA_DIR=./chroma_db
LLM_API_KEY=
LLM_BASE_URL=https://open.bigmodel.cn/api/paas/v4
LLM_MODEL=glm-4-flash
APP_JWT_SECRET=
APP_CORS_ALLOWED_ORIGINS=http://localhost:5173,http://127.0.0.1:5173
APP_OBJECT_STORAGE_ENABLED=false
APP_CLEANUP_ENABLED=false
VITE_API_BASE=/
```

## P2 视频分析输出规则

- 上传成功只表示任务入队，不表示分析完成。
- `UPLOADED`、`PROCESSING`、`COMPLETED`、`FAILED`、`CANCELLED` 必须保持后端、数据库、前端展示一致。
- `COMPLETED` 必须有可解析 `analysisResult`，且包含 Python 分析返回的核心字段。
- Python `/analyze` 返回至少包含 `video_id`、`exercise_type`、`rep_count`、`tips`、`rep_events`、`report_images`、`schema_version`。
- 无姿态点、视频不可打开、动作类型不支持、视频时长超限时必须失败并返回明确错误。
- 关键帧图片必须来自真实视频帧渲染，不得使用占位图。
- 评分、角度、节奏、对称性等指标必须可追溯到分析数据。
- 删除视频时必须同步处理数据库记录、任务记录、源文件、报告图片和对象存储引用。
- 取消任务不得伪装成功；已进入 Python 分析中的任务只能通过状态检查做尽力取消。

## P2 RAG 与评测规则

- 知识库默认本地文件直读，入口为 `KNOWLEDGE_PATH` 或 `src/main/resources/rag/fitness_knowledge_zh.txt`。
- ingest 必须生成稳定 `chunk_id`，并写入 Chroma metadata 与 BM25 metadata。
- 向量检索、BM25 检索、混合检索都必须返回 `chunk_id`。
- 混合检索去重优先使用 `chunk_id`，只有缺失时才允许回退内容键。
- 离线检索评测使用 `ai-service/app/rag/eval_dataset.jsonl` 格式：

```json
{"query_id":"q0001","question":"...","relevant_doc_ids":{"chunk_0001":2}}
```

- qrels/run 标准格式必须由 `evaluation.py` 生成，不得用自然语言答案相似度代替。
- 默认指标优先包含 `map`、`ndcg_cut.10`、`recall.1`、`recall.5`、`recall.10`、`P.1`、`P.5`、`P.10`、`mrr_cut.10`。
- LLM 合成评测集必须经过人工或独立校验后才能作为稳定回归集。
- 修改检索、切块、metadata、rerank、缓存逻辑时，必须运行离线评测或说明阻断。

## P3 验证要求

后端 Java 改动后优先运行：

```powershell
mvn -q test
```

如果 Maven Wrapper 可用，也可运行：

```powershell
.\mvnw.cmd -q test
```

AI 服务 Python 改动后优先运行：

```powershell
python -m compileall ai-service/app ai-service/scripts
```

涉及 RAG 检索或评测的改动，必须运行：

```powershell
python ai-service/scripts/eval_runner.py --dataset ai-service/app/rag/eval_dataset.jsonl --top-k 10 --verbose
```

前端改动后运行：

```powershell
cd frontend
npm.cmd run build
```

部署配置改动后至少检查：

```powershell
docker compose config
docker compose -f docker-compose.cloud.yml config
```

无法验证时必须说明具体阻断，例如依赖未安装、Docker 不可用、网络不可达、数据库/RabbitMQ 未启动。

## P3 Git 最低要求

### 任务收尾 Git 流程（仅当前目录为 Git 仓库执行）

任务全部完成、对应验证通过之后执行：

1. 运行 `git status --short` 展示所有变更清单。
2. 禁止提交：密钥文件、`.env`、虚拟环境、`node_modules`、`target`、`logs`、`uploaded-videos`、`chroma_db`、`__pycache__`、构建产物、运行产物。
3. `git add` 所有源码和文档改动，过滤不需要提交的产物。
4. 根据本次改动自动生成规范提交信息。

提交信息示例：

```text
feat(rag): add pytrec_eval offline retrieval evaluation
fix(video): preserve task cancellation state during analysis
fix(rag): preserve chunk ids in hybrid retrieval
docs: update Claude execution constraints
```

5. `git commit -m "xxx"`。
6. 默认自动提交本地改动；是否 push 以用户明确要求为准。

硬性约束：

- 验证不通过，禁止提交，除非用户明确要求保存失败现场。
- 绝不提交敏感密钥、运行产物、虚拟环境文件。
- main 分支禁止 force push，除非用户明确要求并说明风险。
