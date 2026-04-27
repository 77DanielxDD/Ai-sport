# 任务书验收清单（系统）

## A. 系统设计与开发
- [x] 前后端分离：`frontend + Spring Boot + Python AI`
- [x] MySQL 持久化：用户、视频、分析任务、分析结果
- [x] RabbitMQ 异步分析链路（含 MQ 不可用本地回退）
- [x] AI 动作分析：`PUSHUP / SQUAT / BENCH_PRESS / DEADLIFT`
- [x] 系统设计文档：`docs/system_design.md`

## B. 功能闭环
- [x] 上传视频 -> 异步分析 -> 轮询 -> 报告展示
- [x] 报告含关键帧 `report_images`、建议 `tips`、角度趋势
- [x] `/media/**` 静态资源可访问
- [x] 任务取消与重试（`CANCELLED` + `FAILED/CANCELLED` 可重试）
- [x] 历史管理：单条删除 + 按筛选批量删除

## C. 接口规范
- [x] `GET /api/videos/{id}/analysis` 轮询语义：
  - `200 COMPLETED`
  - `202 UPLOADED/PROCESSING`
  - `409 CANCELLED`
  - `500 FAILED`
  - `404 NOT_FOUND`
- [x] `@ControllerAdvice` 统一错误 JSON

## D. 前端交互
- [x] 全站状态标签统一（StatusPill）
- [x] 历史页/概览页/任务页空态与错误提示完善
- [x] 关键页面中文文案统一修复

## E. 工程与交付
- [x] Dev/Prod 配置分离（profile + 环境变量）
- [x] API 清单文档：`docs/api_reference.md`
- [x] 部署文档：`docs/deployment_guide.md`
- [x] 验收截图模板：`docs/acceptance_screenshots.md`
- [x] API 自动化测试（上传/轮询/取消/删除主链路）
