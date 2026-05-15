# AI-Sport Frontend

## Start
```bash
cd frontend
npm install
npm run dev
```

## Optional env
Create `.env` in `frontend/`:
```bash
VITE_API_BASE=http://127.0.0.1:8080
```

## Pages
- `/login` login and save JWT token
- `/dashboard` health + recent videos
- `/upload` upload short video and trigger analysis
- `/tasks/:videoId` async polling view
- `/reports/:videoId` report images + tips + raw JSON
- `/experiments` start evaluation and view summary files