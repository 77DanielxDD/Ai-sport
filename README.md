# AI Sport — 基于 SpringBoot 与 AI 视觉的健身动作分析系统

AI 驱动的运动视频分析平台。上传训练视频 → 异步 MediaPipe 姿态分析 → 逐次多维度评分 → Agent+RAG 智能问答 → 历史对比追踪。

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
| AI 服务 | Python 3.11, FastAPI, MediaPipe 0.10, OpenCV |
| LLM | DeepSeek API (Agent + RAG) |
| 对象存储 | 腾讯云 COS（可选） |
| 基础设施 | Docker Compose, Nginx |

## 核心功能

### 训练分析闭环
- **上传引导** — 拍摄建议、视频预览、时长/大小校验、上传进度
- **异步任务** — RabbitMQ 解耦，6 阶段进度追踪，自动轮询
- **分析报告** — 逐次多维度评估（深度 45% / 稳定性 20% / 对称性 20% / 节奏加成），加权综合评分
- **8 种动作** — 俯卧撑、深蹲、卧推、硬拉、哑铃推肩、哑铃侧平举、哑铃二头弯举、引体向上

### AI 智能问答
- Agent 编排 5 个工具：知识搜索、分数趋势、训练历史、用户记忆、视频报告
- RAG 检索增强生成，结合用户历史与健身知识库
- 预设问题 + 自由提问，流式输出结构化诊断与训练计划

### 训练成长
- **报告对比** — 9 区对比分析：结论、指标、四维条形、逐次表格、关键帧、趋势、问题变化、建议、历史排名
- **训练趋势** — 30 天评分趋势、性能摘要、系统健康看板

### 系统特性
- JWT 认证 + 速率限制
- 管理后台（用户管理、系统监控、清理任务）
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
| `APP_LLM_API_KEY` | — | DeepSeek API Key |
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
│       ├── rag/          # RAG 检索增强
│       └── task/         # 异步任务管理
├── ai-service/           # Python AI 服务
│   └── app/main.py       # MediaPipe 姿态分析
├── deploy/               # 部署配置
│   └── cloud/            # Nginx + Docker Compose
├── docs/                 # 文档
│   ├── TECH_STACK_AND_SOP.md
│   ├── api_reference.md
│   └── system_design.md
├── PRODUCT.md            # 产品定位
├── DESIGN.md             # 设计系统
└── docker-compose.yml    # 本地基础设施
```

## 设计系统

亮色主题，翡翠绿 `#059669` 强调色，暖白 `#F9F7F4` 底色。DM Sans（UI）+ JetBrains Mono（数据）。WCAG AA 对比度。详见 `DESIGN.md`。

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
