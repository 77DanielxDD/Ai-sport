export default function StatusPill({ status, fallback = "UNKNOWN" }) {
  const raw = status || fallback;
  const code = String(raw).toUpperCase();
  const textMap = {
    UPLOADED: "已上传",
    PROCESSING: "处理中",
    COMPLETED: "已完成",
    FAILED: "失败",
    CANCELLED: "已取消",
    QUEUED: "排队中",
    UNKNOWN: "未知",
    DOWN: "异常",
    METHOD_NOT_ALLOWED: "方法不允许",
    INTERNAL_ERROR: "系统错误",
    FORBIDDEN: "无权限",
    NOT_FOUND: "未找到",
  };
  const cls = `status-pill status-${code.toLowerCase()}`;
  return <span className={cls}>{textMap[code] || code}</span>;
}
