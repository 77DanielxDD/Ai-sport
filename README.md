# AI Sport — 基于 SpringBoot 与 AI 视觉的健身动作分析系统

[![CI/CD](https://github.com/77DanielxDD/Ai-sport/actions/workflows/ci-cd.yml/badge.svg)](https://github.com/77DanielxDD/Ai-sport/actions)
[![Java 17](https://img.shields.io/badge/Java-17-orange)](https://adoptium.net)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring_Boot-3.5-green)](https://spring.io/projects/spring-boot)
[![React 18](https://img.shields.io/badge/React-18-61DAFB)](https://react.dev)

AI 驱动的运动视频分析平台。上传训练视频 → 异步 MediaPipe 姿态分析 → 逐次多维度评分 → Agent+RAG 智能问答 → 历史对比追踪。

🌐 **项目主页**: [77DanielxDD.github.io/Ai-sport](https://77DanielxDD.github.io/Ai-sport)

## 系统架构

```
┌──────────────┐     ┌──────────────────────────────┐     ┌─────────────────┐
│   Frontend   │────▶│   Spring Boot Backend (:8080) │────▶│  MySQL (ai_sport)│
│  React 18    │     │   Java 17 / Maven             │     └─────────────────┘
│  Vite 5      │     └──────────────────────┬────────┘
└──────────────┘                            │
                      ┌─────────────────────┼─────────────────────┐
                      ▼                     ▼                     ▼
              ┌──────────┐         ┌──────────┐          ┌──────────────┐
              │ RabbitMQ │         │  Redis   │          │ Python AI    │
              │ :5673    │         │  :6379   │          │ FastAPI :8000│
              └──────────┘         └──────────┘          └──────────────┘
                                                               │
                                                          MediaPipe
                                                          OpenCV
```

## 技术栈

| 层级 | 技术 |
|------|------|
| Frontend | React 18, Vite 5, react-router 6, zustand 5, DM Sans + JetBrains Mono |
| Backend | Spring Boot 3.5, Java 17, Maven, JPA/Hibernate, JJWT, Actuator |
| 消息队列 | RabbitMQ 3 (spring-boot-starter-amqp) |
| 缓存 | Redis 7 (spring-boot-starter-cache) |
| 数据库 | MySQL 8.4 |
| AI 服务 | Python 3.11, FastAPI, MediaPipe 0.10, OpenCV, LangChain, Chroma, BM25, sentence-transformers |
| LLM 与重排 | GLM-4-Flash（结构化 Agent 回答）+ SiliconFlow `BAAI/bge-reranker-v2-m3`（候选精排，失败时启发式降级） |
| 对象存储 | 腾讯云 COS（可选） |
| CI/CD | GitHub Actions（后端编译/测试/打包 + 前端构建） |
| 基础设施 | Docker Compose, Nginx |

## 核心功能

### 训练分析闭环
- **上传引导** — 拍摄建议、视频预览、时长/大小校验、上传进度
- **异步任务** — RabbitMQ 解耦，`taskId` 幂等状态迁移避免重复消费；SSE 推送状态变化，断开后指数退避轮询兜底
- **分析报告** — 逐次多维度评估（深度 45% / 稳定性 20% / 对称性 20% / 节奏加成），加权综合评分
- **关键帧画廊** — 缩略图网格 + Lightbox 灯箱查看，逐帧姿态标注
- **8 种动作** — 俯卧撑、深蹲、卧推、硬拉、哑铃推肩、哑铃侧平举、哑铃二头弯举、引体向上

### AI 智能问答
- **LangChain Agentic RAG** — Python FastAPI 端 LangChain Agent 编排，Spring Boot 通过 `PythonAgentClient` 优先调用 Python Agent，回退至 Java Agent
- Agent 编排 5 个工具：知识搜索、分数趋势、训练历史、用户记忆、视频报告
- RAG 混合检索：Chroma 稠密向量 + BM25 稀疏召回 → 分数融合 → BGE Reranker 精排；重排 API 不可用时降级为启发式排序
- `/rag/reindex` 执行全量重建索引，`/rag/status` 返回索引状态；小型、低频更新知识库优先保证一致性，不宣称未实现的增量 Hash 热更新
- 预设问题 + 自由提问，返回带意图、工具调用、诊断建议和知识引用的结构化结果
- 问答历史 localStorage 持久化，跨会话保留

### 评测与可验证范围
- **RAG 检索评测** — 使用 `pytrec_eval` 读取标准 `qrels/run`，固定集当前包含 8 条与真实 `chunk_id` 对齐的问题；输出 MAP、NDCG@10、Recall@K、Precision@K 与 Reciprocal Rank。该层只评估检索排序，不以 LLM 裁判替代标准检索指标。
- **Agent 规划评测** — 固定集 20 条，覆盖动作纠正、训练计划、趋势回顾和通用知识四类意图。已记录的离线结果中，意图识别准确率、工具计划完全匹配率、工具选择 Precision/Recall/F1、grounding 规划覆盖率均为 100%。该结果仅说明固定集的路由与工具规划正确，不等同于所有生成答案“100% 正确”。
- **真实 API 回归** — Java `/api/agent/qa` 到 Python `/agent/chat` 的 20 条自动请求已记录接口成功率 20/20，P50/P95 为 868ms/1002ms。知识检索相关的 15 条问题均返回引用；趋势问题在无可用历史数据时返回数据不足提示，避免伪造个人结论。

### 训练成长
- **报告对比** — 9 区对比分析：结论、指标、四维条形、逐次表格、关键帧、趋势、问题变化、建议、历史排名
- **训练趋势** — 30 天评分趋势、性能摘要、系统健康看板

### 系统特性
- JWT 认证 + 速率限制
- 管理后台（用户管理、系统监控、清理任务）
- GitHub Actions CI/CD（自动编译、测试、打包）
- COS 对象存储（可选）
- JMeter 压力测试覆盖

## 本地开发

### 前置条件
- Java 17+, Maven 3.9+
- Node.js 18+
- Python 3.11+
- Docker Desktop（Redis + RabbitMQ）

### 快速启动

```powershell
# 1. 启动基础设施（MySQL 用本地 Windows Service）
docker compose up -d redis rabbitmq

# 2. 设置数据库密码
$env:DEV_DB_PASSWORD = "changeme"

# 3. 启动 AI 服务
cd ai-service
pip install -r requirements.txt
python main.py

# 4. 启动后端
cd ..
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# 5. 启动前端
cd frontend
npm install
npx vite
```

一键启动脚本：

```powershell
$env:DEV_DB_PASSWORD = "changeme"
powershell -ExecutionPolicy Bypass -File scripts/run_dev.ps1
```

访问地址：
- 前端：http://localhost:5173
- 后端：http://localhost:8080
- AI 服务：http://localhost:8000

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DEV_DB_PASSWORD` | `changeme` | MySQL root 密码 |
| `APP_LLM_API_KEY` | — | GLM-4-flash API Key（智谱 AI） |
| `APP_LLM_ENABLED` | `true` | 启用 LLM 增强 |
| `DEV_OBJECT_STORAGE_ENABLED` | `false` | 启用 COS 存储 |
| `APP_MAX_FILE_SIZE` | `150MB` | 上传大小上限 |

完整配置见 `env.example` 和 `docs/TECH_STACK_AND_SOP.md`。

## 项目结构

```
ai-sport/
├── frontend/             # React SPA (11 页面)
│   ├── src/pages/        # 页面组件
│   ├── src/components/   # 通用组件
│   └── src/styles.css    # 全局样式
├── src/                  # Spring Boot 后端
│   └── main/java/com/example/aisport/
│       ├── agent/        # AI Agent 编排
│       ├── controller/   # 10 个 REST 控制器
│       ├── service/      # 核心业务服务
│       └── task/         # 异步任务管理
├── ai-service/           # Python AI 服务
│   ├── app/main.py       # MediaPipe 姿态分析 + FastAPI
│   ├── app/agent/        # LangChain Agent 编排
│   ├── app/rag/          # Chroma + BM25 混合检索
│   └── app/clients/      # LLM / Backend / Redis 客户端
├── deploy/               # 部署配置
│   └── cloud/            # Nginx + Docker Compose
├── .github/workflows/    # CI/CD 流水线
├── docs/                 # 文档
│   ├── TECH_STACK_AND_SOP.md
│   ├── api_reference.md
│   └── system_design.md
├── 项目指南.md            # 架构说明、评测边界与面试问答
└── docker-compose.yml    # 本地基础设施
```


## 云端部署

```bash
cp env.example .env
# 编辑 .env 填入实际密码和 COS 凭证
docker compose -f docker-compose.cloud.yml up -d
```

详细步骤见 `docs/cloud_deployment.md`。

## 文档

- [技术栈与 SOP](docs/TECH_STACK_AND_SOP.md)
- [API 参考](docs/api_reference.md)
- [系统设计](docs/system_design.md)
- [云端部署](docs/cloud_deployment.md)
- [验收清单](docs/acceptance_checklist.md)
- [项目指南](项目指南.md)
