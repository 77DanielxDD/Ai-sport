# JMeter 压测方案（上传 + 轮询分析）

## 目标
- 5 并发持续上传短视频（建议每个视频 <= 15 秒）
- 上传成功后轮询 `GET /api/videos/{id}/analysis`
- 兼容轮询返回 `202`（处理中）、`200`（完成）、`500/409`（失败）
- 统计端到端耗时：从上传返回到拿到 `COMPLETED`

## 文件
- `jmeter/ai-sport-upload-poll.jmx`：主测试计划
- `jmeter/testdata/upload_cases.csv`：上传数据源（视频路径、用户名、密码、动作类型）
- `jmeter/testdata/videos/`：压测样本视频目录（请放入 <=15s 视频）

## 动作覆盖
当前 CSV 已覆盖 4 种动作：
- `PUSHUP`
- `SQUAT`
- `BENCH_PRESS`
- `DEADLIFT`

## 前置条件
1. 启动 Java 服务（默认 `http://127.0.0.1:8080`）
2. 在 `jmeter/testdata/videos/` 放入对应视频：
   - `pushup_10s.mp4`
   - `squat_12s.mp4`
   - `bench_press_12s.mp4`
   - `deadlift_14s.mp4`
3. 如文件名不同，修改 `jmeter/testdata/upload_cases.csv`
4. 确认 CSV 中用户密码可用于 `/api/users/login`

## 关键逻辑
- 线程组：`5` 线程、无限循环、按持续时间结束
- 登录接口：`POST /api/users/login`（每轮先登录获取 JWT）
- 上传接口：`POST /api/videos/upload`（multipart：`file` + `exerciseType`，携带 JWT）
- 登录后提取 `token` 到 `auth_token`
- 上传后提取 `videoId`，记录 `e2e_start_ms`
- 轮询接口：`GET /api/videos/${video_id}/analysis`
  - `202`：继续轮询
  - `200`：结束轮询，写入样本 `E2E_COMPLETE_MS`
  - `500/409`：结束轮询，写入失败样本 `E2E_FAILED`

## 运行命令
在项目根目录执行：

```bash
jmeter -n -t jmeter/ai-sport-upload-poll.jmx \
  -l jmeter/results/run.jtl \
  -e -o jmeter/results/html \
  -JHOST=127.0.0.1 \
  -JPORT=8080 \
  -JPROTOCOL=http \
  -JDURATION_SEC=120 \
  -JPOLL_MAX=120 \
  -JPOLL_INTERVAL_MS=1000 \
  -JCSV_PATH=jmeter/testdata/upload_cases.csv
```

## 输出平均值 / P95
1. 打开 `jmeter/results/html/index.html`
2. 在统计表中筛选样本名 `E2E_COMPLETE_MS`
3. 读取：
   - `Average`：平均端到端耗时
   - `95th pct`：端到端 P95

## 可调参数
- `DURATION_SEC`：压测持续时长（秒）
- `POLL_MAX`：单次上传最大轮询次数
- `POLL_INTERVAL_MS`：轮询间隔
- `HOST/PORT/PROTOCOL`：目标服务地址
- `CSV_PATH`：上传数据 CSV

## 注意事项
- 如果 `E2E_FAILED` 比例偏高，优先检查后端日志和 Python AI 服务可用性。
- 为满足任务书指标，压测样本请控制在 15 秒以内。
