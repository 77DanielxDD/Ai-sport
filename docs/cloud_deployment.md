# 云端部署指南（Linux 云服务器）

## 1. 目标
将当前项目从本地联调升级为云端部署。部署后通过一个公网入口访问前端与后端 API。

## 2. 部署架构
- `gateway`：Nginx 统一入口（80 端口）
- `frontend`：Vite 打包后的静态站点
- `backend`：Spring Boot 服务（容器内 8080）
- `ai`：Python AI 服务（可选，`with-ai` profile）
- `mysql`：业务数据库
- `rabbitmq`：异步任务队列

如果你已经有独立的 Python AI 服务，直接在 `.env` 里设置 `AI_SERVICE_BASE_URL`。如果你有 AI 服务镜像，也可以通过 `with-ai` profile 一起拉起。

## 3. 云服务器准备
- 系统：Ubuntu 22.04 / Debian 12
- 配置建议：2 vCPU, 4 GB RAM, 40 GB 磁盘
- 安装：Docker + Docker Compose 插件
- 放行端口：`80`（公网访问）

## 4. 配置环境变量
在项目根目录执行：

```bash
cp deploy/cloud/.env.example deploy/cloud/.env
```

编辑 `deploy/cloud/.env`，至少修改以下项：
- `MYSQL_ROOT_PASSWORD`
- `MYSQL_PASSWORD`
- `APP_JWT_SECRET`（长度 >= 32）
- `APP_CORS_ALLOWED_ORIGINS`（填你的域名或服务器 IP）
- `AI_SERVICE_BASE_URL`（独立服务地址或 `http://ai:8000`）
- `AI_SERVICE_IMAGE`（仅在 `with-ai` 时使用）
- COS 相关参数（如果使用 COS）

## 5. 启动云端部署
```bash
chmod +x scripts/cloud_up.sh scripts/cloud_down.sh
./scripts/cloud_up.sh
```

如果要一并启动 AI 容器：
```bash
WITH_AI=true ./scripts/cloud_up.sh
```

## 6. 验证
- 首页：`http://<你的云服务器IP>/`
- 后端健康：`http://<你的云服务器IP>/api/system/health`
- Actuator 健康：`http://<你的云服务器IP>/actuator/health`

查看容器状态：
```bash
docker compose --env-file deploy/cloud/.env -f docker-compose.cloud.yml ps
```

查看日志：
```bash
docker compose --env-file deploy/cloud/.env -f docker-compose.cloud.yml logs -f backend
docker compose --env-file deploy/cloud/.env -f docker-compose.cloud.yml logs -f gateway
```

## 7. 停止
```bash
./scripts/cloud_down.sh
```

## 8. 常见问题
- 后端报 RabbitMQ 连接失败：确认 `rabbitmq` 容器为 `healthy`，并检查 `RABBITMQ_USER/PASSWORD`。
- 上传后分析一直处理中：确认 `AI_SERVICE_BASE_URL` 可从 `backend` 容器访问。
- 前端 404：确认 `gateway` 正常运行，且域名或 IP 指向服务器公网地址。
