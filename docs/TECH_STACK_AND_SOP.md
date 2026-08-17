# AI Sport 技术栈与完整 SOP

> 2026-06-01 | 基于 `main` 分支 commit `37d150e`

---

## 1. 技术栈

### 1.1 总体架构

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

### 1.2 各层详细

#### Backend（Spring Boot）

| 项 | 版本/选型 |
|---|---|
| Java | 17 (Amazon Corretto 21.0.11) |
| Spring Boot | 3.5.9 |
| 构建 | Maven 3.9.15 |
| Web 框架 | spring-boot-starter-web (Tomcat) |
| ORM | spring-boot-starter-data-jpa (Hibernate) |
| 数据库 | MySQL 8.4 (ai_sport) |
| 消息队列 | spring-boot-starter-amqp (RabbitMQ 3-management) |
| 缓存 | spring-boot-starter-data-redis + spring-boot-starter-cache |
| 认证 | spring-boot-starter-security + JJWT 0.12.6 (JWT Bearer Token) |
| 对象存储 | COS (腾讯云 cos_api 5.6.x) |
| 监控 | spring-boot-starter-actuator (health/info/metrics/prometheus/mappings) |
| 验证 | spring-boot-starter-validation |
| 工具 | Lombok, Jackson |

**核心模块：**

| 包 | 职责 |
|---|---|
| `agent/` | AI Agent 编排器，含规则路由、工具注册、LLM 客户端 |
| `agent/tools/` | 知识搜索、分数趋势、训练历史、用户记忆、视频报告 5 个工具 |
| `config/` | CORS、Security、RabbitMQ、异步、定时、媒体资源映射 |
| `controller/` | 10 个 REST 控制器 |
| `entity/` | User、ExerciseVideo、AnalysisResult 3 个 JPA 实体 |
| `memory/` | 用户训练记忆的定时刷新与持久化 |
| `security/` | JWT 过滤器、速率限制过滤器 |
| `service/` | 核心业务：视频分析、存储、LLM 洞察、训练洞察、MQ 恢复、对象存储、查询缓存 |
| `task/` | 异步分析任务的持久化与生命周期管理 |

#### Frontend（React SPA）

| 项 | 版本/选型 |
|---|---|
| React | 18.3.1 |
| 构建工具 | Vite 5.4.19 |
| 路由 | react-router-dom 6.30.1 |
| 状态管理 | zustand 5.0.13 |
| 样式 | 纯 CSS (styles.css)，CSS 变量主题 |
| API | fetch + JWT Bearer Token |

**页面（11 个路由）：**

| 路由 | 页面 | 说明 |
|---|---|---|
| `/login` | LoginPage | 登录 |
| `/register` | RegisterPage | 注册 |
| `/dashboard` | DashboardPage | 系统概览（趋势、最近分数） |
| `/upload` | UploadPage | 上传视频发起分析 |
| `/history` | HistoryPage | 视频历史列表 |
| `/compare` | ComparePage | 两两对比报告 |
| `/qa` | QaPage | RAG 知识问答 |
| `/profile` | ProfilePage | 个人中心 |
| `/tasks/:videoId` | TaskPage | 分析任务进度 |
| `/reports/:videoId` | ReportPage | 分析报告（多维度逐次数据 + 关键帧） |
| `/admin` | AdminPage | 管理员控制台 |

#### Python AI 服务

| 项 | 版本 |
|---|---|
| Python | 3.11.9 |
| Web 框架 | FastAPI 0.115.6 |
| ASGI Server | uvicorn 0.32.1 |
| 姿态估计 | mediapipe 0.10.14 |
| 图像处理 | opencv-python 4.10.0.84 |
| 数据验证 | pydantic 2.10.3 |

**核心逻辑：** 逐帧读取视频 → MediaPipe Pose → 检测局部极小角度作为 rep 事件 → 计算深度/节奏/稳定性/对称性 → 渲染关键帧图片 → 返回结构化 JSON。

**支持动作类型：** PUSHUP, SQUAT, BENCH_PRESS, DEADLIFT, DUMBBELL_SHOULDER_PRESS, DUMBBELL_LATERAL_RAISE, DUMBBELL_BICEP_CURL, PULL_UP（含别名映射）。

#### 基础设施

| 服务 | 版本 | 端口 | 管理方式 |
|---|---|---|---|
| MySQL | 8.4 (Windows Service 或 Docker) | 3306 | 本地 Windows 服务 |
| Redis | 7-alpine (Docker) | 6379 | Docker Compose |
| RabbitMQ | 3-management (Docker) | 5673→5672 / 15672 | Docker Compose |
| Python AI | Docker / 本地 Python | 8000 | 本地 Python 进程 |

---

## 2. 环境变量

### 2.1 后端必需

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DEV_DB_PASSWORD` | `changeme` | MySQL root 密码 |
| `APP_MEDIA_BASE_DIR` | `./uploaded-videos/output` | 媒体文件读取目录 |

### 2.2 后端可选

| 变量 | 默认值 | 说明 |
|---|---|---|
| `APP_REDIS_CACHE_ENABLED` | `false` | 启用 Redis 查询缓存 |
| `APP_REDIS_HOST` | `127.0.0.1` | Redis 地址 |
| `APP_REDIS_PORT` | `6379` | Redis 端口 |
| `APP_LLM_API_KEY` | - | GLM-4-flash API Key（智谱 AI，LLM 增强用） |
| `APP_LLM_ENABLED` | `true` | 启用 LLM 洞察改写 |
| `DEV_OBJECT_STORAGE_ENABLED` | `false` | 启用 COS 对象存储 |
| `DEV_OBJECT_STORAGE_ACCESS_KEY` | - | COS SecretId |
| `DEV_OBJECT_STORAGE_SECRET_KEY` | - | COS SecretKey |
| `DEV_OBJECT_STORAGE_BUCKET` | - | COS Bucket |
| `DEV_OBJECT_STORAGE_REGION` | `ap-guangzhou` | COS 区域 |
| `DEV_OBJECT_STORAGE_PUBLIC_BASE_URL` | - | COS 公网访问域名 |

### 2.3 Python AI 服务

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AI_MEDIA_BASE_DIR` | `./uploaded-videos/output` | 关键帧图片输出目录 |

### 2.4 前端

| 变量 | 默认值 | 说明 |
|---|---|---|
| `VITE_API_BASE` | `http://127.0.0.1:8080` | 后端 API 地址 |

---

## 3. 完整 SOP

### 3.1 前置条件

- Windows 11 / Linux，Git 已安装
- Java 17+（推荐 Amazon Corretto 21）
- Maven 3.9+
- Python 3.11+
- Docker Desktop（Redis + RabbitMQ）
- MySQL 8.4（Windows Service 或 Docker）

### 3.2 首次环境搭建

```powershell
# 1. 克隆仓库
git clone https://github.com/77DanielxDD/ai-sport.git
cd ai-sport

# 2. 安装 Python 依赖
pip install -r ai-service/requirements.txt

# 3. 安装前端依赖
cd frontend
npm install
cd ..

# 4. 启动 Docker 基础设施（Redis + RabbitMQ，可按需加 MySQL）
docker compose up -d redis rabbitmq

# 5. 确保 MySQL 可连接（如果用 Windows MySQL 服务，确保 ai_sport 库已存在）
# 如果用 Docker MySQL:
#   docker compose up -d mysql redis rabbitmq
#   首次启动后 MySQL 会自动创建 ai_sport 库

# 6. 设置环境变量（二选一）
# 方案 A：通过 PowerShell Session
$env:DEV_DB_PASSWORD = "changeme"
$env:APP_REDIS_CACHE_ENABLED = "true"

# 方案 B：写入用户环境变量（永久生效）
# [System.Environment]::SetEnvironmentVariable('DEV_DB_PASSWORD', 'changeme', 'User')

# 7. 创建上传目录
New-Item -ItemType Directory -Force -Path uploaded-videos/output
```

### 3.3 日常开发一键启动

```powershell
# 推荐：自动停止旧进程 → 启动 Docker → AI → 后端 → 前端
powershell -ExecutionPolicy Bypass -File scripts/run_dev.ps1

# 如果使用 Windows MySQL 服务（非 Docker MySQL），加 -SkipMySql
powershell -ExecutionPolicy Bypass -File scripts/run_dev.ps1 -SkipMySql
```

启动后访问：
- 前端：http://127.0.0.1:5173
- 后端：http://127.0.0.1:8080
- 健康检查：http://127.0.0.1:8080/api/system/health
- AI 服务：http://127.0.0.1:8000/health

### 3.4 停止所有服务

```powershell
powershell -ExecutionPolicy Bypass -File scripts/dev_down.ps1

# 如需同时停止 RabbitMQ：
powershell -ExecutionPolicy Bypass -File scripts/dev_down.ps1 -StopRabbitMq
```

### 3.5 运行测试

```powershell
# 全部测试（跳过需要 MySQL 的集成测试）
mvn test -DskipTests=false

# 仅单元测试
mvn test -Dtest=TrainingInsightServiceTest,LLMInsightServiceTest,AgentOrchestratorTest

# 集成测试（需要 MySQL 连接和 DEV_DB_PASSWORD 环境变量）
$env:DEV_DB_PASSWORD = "changeme"
mvn test -Dtest=RealMySqlWhiteBoxIntegrationTest,RealAnalysisChainIntegrationTest
```

预期结果：约 55 个测试，50+ passed，若干 skipped（需要真实外部服务）。

### 3.6 前端构建

```powershell
cd frontend
npm run build         # 生产构建 → dist/
npm run dev           # 开发服务器 :5173
npm run preview       # 预览生产构建
```

### 3.7 数据库操作

```powershell
# 连接 MySQL
mysql -u root -pchangeme ai_sport

# 常用查询
SELECT id, status, exercise_type, uploaded_at FROM exercise_videos ORDER BY id DESC LIMIT 10;
SELECT id, video_id, status FROM analysis_tasks ORDER BY id DESC LIMIT 10;
SELECT COUNT(*) FROM exercise_videos WHERE status = 'COMPLETED';
```

### 3.8 Docker 管理

```powershell
# 查看运行中的容器
docker ps

# 查看所有容器（含已停止）
docker ps -a

# 启动基础设施
docker compose up -d redis rabbitmq

# 停止
docker compose stop

# 完全清除（含数据卷）
docker compose down -v

# 日志
docker compose logs -f --tail 50
```

### 3.9 手动启动各服务（不用脚本）

```powershell
# 1) 启动 Python AI
cd ai-service
$env:AI_MEDIA_BASE_DIR = "E:\ai-sport\ai-sport-main\uploaded-videos\output"
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000

# 2) 启动后端
cd ..
$env:APP_MEDIA_BASE_DIR = "E:\ai-sport\ai-sport-main\uploaded-videos\output"
$env:DEV_DB_PASSWORD = "changeme"
$env:APP_REDIS_CACHE_ENABLED = "true"
mvn spring-boot:run

# 3) 启动前端
cd frontend
npm run dev -- --host 0.0.0.0 --port 5173
```

### 3.10 故障排查

| 症状 | 检查 | 修复 |
|---|---|---|
| 后端无法连接 MySQL | `mysql -u root -p$env:DEV_DB_PASSWORD ai_sport` | 检查 Windows MySQL 服务是否运行，密码是否正确 |
| `/media/` 图片 404 | `ls uploaded-videos/output/` | 确保 `APP_MEDIA_BASE_DIR` 和 `AI_MEDIA_BASE_DIR` 指向同一目录 |
| 前端关键帧不显示 | 浏览器 F12 → Network → 检查图片 URL | 旧数据可能引用 COS URL（需 COS 可访问）；新数据检查 `/media/` 路径 |
| Python AI 不可用 | `curl 127.0.0.1:8000/health` | 安装 mediapipe：`pip install mediapipe==0.10.14` |
| Health 显示 RABBITMQ DOWN | `docker ps \| grep rabbitmq` | `docker compose up -d rabbitmq` |
| 端口 8080 被占用 | `netstat -ano \| findstr :8080` | 杀掉占用进程或改 `SERVER_PORT` |
| Maven 编译失败 | `mvn compile` | 检查 JDK 17+、Maven 3.9+ |
| Docker Compose 权限错误 | `open config.json: Access denied` | 修 `~/.docker/config.json` 的 NTFS 权限 |

---

## 4. 项目结构速览

```
ai-sport-main/
├── ai-service/                     # Python AI 服务
│   ├── app/main.py                 # FastAPI 入口 + MediaPipe 分析逻辑
│   ├── requirements.txt
│   └── Dockerfile
├── frontend/                       # React SPA
│   ├── src/
│   │   ├── api.js                  # API 封装 + JWT 拦截
│   │   ├── App.jsx                 # 路由定义
│   │   ├── store.js                # zustand 状态管理
│   │   ├── styles.css              # 全局样式
│   │   ├── components/             # 3 个通用组件
│   │   ├── pages/                  # 11 个页面组件
│   │   └── utils/
│   └── package.json
├── src/                            # Spring Boot 后端
│   ├── main/java/com/example/aisport/
│   │   ├── agent/                  # AI Agent 编排
│   │   ├── config/                 # Spring 配置
│   │   ├── controller/             # REST 控制器 (10)
│   │   ├── dto/                    # 数据传输对象
│   │   ├── entity/                 # JPA 实体 (3)
│   │   ├── exception/              # 全局异常处理
│   │   ├── memory/                 # 用户训练记忆
│   │   ├── repository/             # 数据仓库
│   │   ├── security/               # JWT + 速率限制
│   │   ├── service/                # 业务服务 (14)
│   │   └── task/                   # 异步分析任务
│   ├── main/resources/
│   │   ├── application.properties
│   │   ├── application-dev.properties
│   │   └── application-prod.properties
│   └── test/                       # 测试
├── scripts/                        # 运维脚本
│   ├── run_dev.ps1                 # 推荐：一键启动
│   ├── dev_down.ps1                # 停止所有
│   ├── one_click_up.ps1            # 完整版启动（含 PID 管理）
│   ├── smoke_e2e.ps1               # E2E 冒烟测试
│   └── run_jmeter.ps1              # JMeter 性能测试
├── docker-compose.yml              # Docker 基础设施
├── docker-compose.cloud.yml        # 云部署 Compose
├── pom.xml                         # Maven 配置
└── docs/                           # 文档
```

---

## 5. 数据流（以一次视频分析为例）

```
1. 用户上传视频
   POST /api/videos/upload (multipart)
   → VideoService 保存文件 → 写 exercise_videos 表 → 创建 AnalysisTask

2. 分析队列消费
   RabbitMQ video-analysis-queue
   → VideoService.analyzeVideo()
   → 调用 Python AI POST /analyze
   → Python: MediaPipe 逐帧 → 检测 rep → 渲染关键帧 → 计算多维度指标
   → 返回 JSON（rep_events, report_images, tips, rhythm, symmetry）

3. 结果存储
   → VideoStorageService 处理图片（COS 或本地）
   → 写 exercise_videos.analysis_result (JSON 列)
   → 写 analysis_results 表
   → 更新 AnalysisTask 状态为 COMPLETED

4. 前端展示
   GET /api/videos/{id}/analysis
   → 读取 analysis_result JSON
   → TrainingInsightService 计算训练评分 + 逐次评分
   → 前端 ReportPage 渲染多维度表格 + 关键帧图集 + 角度趋势图
```
