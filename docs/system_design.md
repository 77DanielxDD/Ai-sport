# 系统架构与数据模型

## 技术架构图
```mermaid
flowchart LR
  FE[Vue/React Frontend] -->|REST API| BE[Spring Boot API]
  BE --> DB[(MySQL)]
  BE --> MQ[(RabbitMQ)]
  MQ --> CONSUMER[VideoAnalysisConsumer]
  CONSUMER --> PY[Python AI Service]
  PY --> MEDIA[(Python output)]
  FE -->|/media/**| MEDIA
  BE -->|Run Experiment| EVAL[Evaluation Script]
```

## E-R 图
```mermaid
erDiagram
  USERS ||--o{ EXERCISE_VIDEOS : uploads
  EXERCISE_VIDEOS ||--o{ ANALYSIS_TASKS : has
  EXERCISE_VIDEOS ||--o| ANALYSIS_RESULTS : stores

  USERS {
    bigint id PK
    string username UK
    string password
    string email
    datetime created_at
  }

  EXERCISE_VIDEOS {
    bigint id PK
    bigint user_id FK
    string original_file_name
    string stored_file_path
    string exercise_type
    string status
    text analysis_result
    string error_code
    text error_message
    string analysis_schema_version
    datetime uploaded_at
    datetime processed_at
  }

  ANALYSIS_TASKS {
    bigint id PK
    bigint video_id FK
    string status
    int attempt
    datetime queued_at
    datetime started_at
    datetime finished_at
    string error_code
    text error_message
    string correlation_id
  }

  ANALYSIS_RESULTS {
    bigint id PK
    bigint video_id UK
    string exercise_type
    int rep_count
    double overall_score
    text overall_feedback
    longtext rep_events_json
    text result_json_path
    int processing_time_ms
    datetime analyzed_at
  }
```

## 核心流程（闭环）
1. 用户上传视频到 Spring Boot。
2. 后端创建任务并投递 RabbitMQ（若 MQ 不可用则自动降级到本地异步执行）。
3. Python AI 服务分析视频，输出 `rep_count`、`rep_events`、`report_images`、`tips`。
4. Java 将 Python JSON 原样入库并更新任务状态。
5. 前端轮询 `/api/videos/{id}/analysis`：
   - `202`：处理中
   - `200`：展示报告
   - `500/409`：显示失败信息
