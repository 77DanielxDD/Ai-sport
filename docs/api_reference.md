# API 接口清单

## 认证
- `POST /api/users/register`：注册并返回 JWT
- `POST /api/users/login`：登录并返回 JWT
- `GET /api/users/me`：当前用户信息

## 视频与分析
- `POST /api/videos/upload`：上传视频并创建分析任务
- `GET /api/videos`：我的视频列表
  - 支持：`status`、`exerciseType`、`page`、`size`
- `GET /api/videos/{id}`：单视频摘要
- `GET /api/videos/{id}/status`：任务状态与基础耗时
- `GET /api/videos/{id}/analysis`：轮询接口
  - `200 COMPLETED`
  - `202 UPLOADED/PROCESSING`
  - `409 CANCELLED`
  - `500 FAILED`
  - `404 NOT_FOUND`
- `POST /api/videos/{id}/retry`：重试（FAILED/CANCELLED）
- `POST /api/videos/{id}/cancel`：取消进行中的任务
- `DELETE /api/videos/{id}`：删除单视频（含报告与任务）
- `DELETE /api/videos`：批量删除我的视频
  - 支持：`status`、`exerciseType`

## 任务
- `GET /api/tasks/{taskId}`：任务详情（含耗时字段）
  - `queueMs`、`runMs`、`totalMs`、`canCancel`
- `GET /api/tasks/video/{videoId}`：视频任务历史
  - 返回 `{ videoId, count, items: [...] }`

## 实验评测
- `POST /api/experiments/run`：发起评测
- `GET /api/experiments`：评测运行列表
- `GET /api/experiments/{runId}`：运行状态
- `GET /api/experiments/{runId}/summary`：评测汇总

## 系统
- `GET /api/system/health`：系统健康状态

## 静态媒体
- `GET /media/**`：关键帧图片访问
