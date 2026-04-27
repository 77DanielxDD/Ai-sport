# 部署指南（Dev/Prod）

## 1. 组件与端口
- 前端：Vite（默认 `5173`）
- 后端：Spring Boot（默认 `8080`）
- AI 服务：FastAPI/Uvicorn（默认 `8000`）
- MySQL：默认 `3306`
- RabbitMQ：开发环境默认 `5673`（按你的本地配置）

## 2. 环境配置
配置已拆分为：
- `src/main/resources/application.properties`：公共参数 + 环境变量占位
- `src/main/resources/application-dev.properties`：开发环境参数
- `src/main/resources/application-prod.properties`：生产环境参数

通过环境变量切换：
```powershell
$env:SPRING_PROFILES_ACTIVE="dev"   # 或 prod
```

## 3. 开发环境启动
1. 启动 MySQL / RabbitMQ。
2. 启动 Python AI 服务：
```powershell
cd "D:\BaiduNetdiskDownload\Ai-Sport(python)"
.\.venv\Scripts\python.exe -m uvicorn ai_service.api_server:app --host 127.0.0.1 --port 8000
```
3. 启动 Spring Boot：
```powershell
cd "D:\BaiduNetdiskDownload\Ai-Sport"
mvn spring-boot:run
```
4. 启动前端：
```powershell
cd "D:\BaiduNetdiskDownload\Ai-Sport\frontend"
npm run dev
```

## 4. 生产环境关键变量（最小集）
```text
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:mysql://<host>:3306/<db>?useSSL=false&serverTimezone=UTC
DB_USERNAME=<username>
DB_PASSWORD=<password>
RABBIT_HOST=<host>
RABBIT_PORT=5672
RABBIT_USERNAME=<username>
RABBIT_PASSWORD=<password>
AI_SERVICE_BASE_URL=http://<ai-host>:8000
APP_MEDIA_BASE_DIR=/data/ai-sport/output
APP_JWT_SECRET=<32+ chars secret>
APP_RATE_LIMIT_RPM=300
APP_CORS_ALLOWED_ORIGINS=https://<your-frontend-domain>
```

## 5. 健康检查
- Java：`GET /api/system/health`
- Python：`GET /health`

## 6. 常见问题
- 500 且提示 Python 连接失败：确认 AI 服务在 `8000` 已启动。
- 任务长时间 `UPLOADED/PROCESSING`：检查 RabbitMQ 与消费者日志。
- 图片访问失败：检查 `app.media.base-dir` 是否指向 Python `output` 目录。
