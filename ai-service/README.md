# AI Service (FastAPI + MediaPipe)

This service implements the thesis-required AI analysis endpoints:

- `GET /health`
- `POST /analyze`

## Run locally

```bash
cd ai-service
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## Environment variables

- `AI_MEDIA_BASE_DIR`: output directory for report images.  
  Default: `./uploaded-videos/output`

## Response fields (`/analyze`)

- `video_id`
- `exercise_type`
- `rep_count`
- `tips` (`rep_index`, `min_angle`, `tip`)
- `rep_events`
- `report_images` (relative URLs, e.g. `/media/{videoId}/rep_01.png`)
- `processing_time_ms`
- `schema_version`
