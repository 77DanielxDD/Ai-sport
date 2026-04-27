# COS Object Storage Setup

This project now supports Tencent COS for:

- Original video upload storage
- Report frame image storage
- Cleanup deletion on video/user delete and scheduled cleanup

## 1. Enable COS in Spring Boot

Set environment variables (or write into your dev profile):

```powershell
$env:DEV_OBJECT_STORAGE_ENABLED="true"
$env:DEV_OBJECT_STORAGE_PROVIDER="cos"
$env:DEV_OBJECT_STORAGE_REGION="ap-guangzhou"
$env:DEV_OBJECT_STORAGE_BUCKET="your-bucket-125xxxxxxx"
$env:DEV_OBJECT_STORAGE_ACCESS_KEY="AKIDxxxxxxxx"
$env:DEV_OBJECT_STORAGE_SECRET_KEY="xxxxxxxx"
$env:DEV_OBJECT_STORAGE_PUBLIC_BASE_URL="https://your-bucket-125xxxxxxx.cos.ap-guangzhou.myqcloud.com"
```

Optional for intranet/private network endpoint routing:

```powershell
$env:DEV_OBJECT_STORAGE_ENDPOINT_SUFFIX="cos.ap-guangzhou.myqcloud.com"
```

## 2. Enable Python direct upload to COS

Install SDK in Python venv:

```powershell
cd "D:\BaiduNetdiskDownload\Ai-Sport(python)"
.\.venv\Scripts\python.exe -m pip install qcloud-cos-sdk-v5
```

Set Python env:

```powershell
$env:AI_COS_ENABLED="true"
$env:AI_COS_REGION="ap-guangzhou"
$env:AI_COS_BUCKET="your-bucket-125xxxxxxx"
$env:AI_COS_SECRET_ID="AKIDxxxxxxxx"
$env:AI_COS_SECRET_KEY="xxxxxxxx"
$env:AI_COS_PUBLIC_BASE_URL="https://your-bucket-125xxxxxxx.cos.ap-guangzhou.myqcloud.com"
```

## 3. Python output local dir (fallback only)

When `AI_COS_ENABLED=false` or SDK is missing, Python falls back to local output (`/media/...`), and Java will upload/convert.

So keep:

- `APP_MEDIA_BASE_DIR` / `DEV_MEDIA_BASE_DIR` pointing to Python output root
- Java can access that directory

## 4. Behavior

- Upload:
  - File goes to COS key: `videos/{userId}/{date}/{timestamp_filename}`
  - DB `storedFilePath` stores `cos://{bucket}/{key}`
- Analyze:
  - Java downloads COS video to temp file
  - Sends temp path to Python `/analyze`
  - Python uploads report images to COS key: `reports/{videoId}/{filename}` and returns COS public URLs + `report_image_keys`
  - Java keeps returned URLs/keys directly
- Delete/Cleanup:
  - Deletes COS video object
  - Deletes COS report image objects (via `report_image_keys`)

## 5. One-command startup

Use the wrapper script (it injects both Java and Python COS env vars, then starts all services):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/dev_up_cos.ps1 `
  -CosBucket "your-bucket-125xxxxxxx" `
  -CosSecretId "AKIDxxxxxxxx" `
  -CosSecretKey "xxxxxxxx" `
  -CosPublicBaseUrl "https://your-bucket-125xxxxxxx.cos.ap-guangzhou.myqcloud.com"
```
