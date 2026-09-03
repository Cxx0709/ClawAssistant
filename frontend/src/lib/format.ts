/** 毫秒 → 人类可读耗时：`853` → `0.9s`，`4200` → `4.2s`。 */
export function fmtDuration(ms?: number): string {
  if (ms == null || Number.isNaN(ms) || ms < 0) return '';
  if (ms < 1000) return `${Math.max(1, Math.round(ms / 100)) / 10}s`;
  const s = ms / 1000;
  return `${Math.round(s * 10) / 10}s`;
}

const CATEGORY_LABELS: Record<string, string> = {
  PREFERENCE: '偏好',
  RULE: '规则',
  FACT: '事实',
  GOAL: '目标',
  EXPERIENCE: '经验',
};

/** 后端英文分类/状态 → 中文展示。未知名直接回显。 */
export function categoryLabel(raw?: string | null): string {
  if (!raw) return '记忆';
  return CATEGORY_LABELS[raw] ?? raw.toLowerCase();
}

const GOAL_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '进行中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
};

export function goalStatusLabel(raw?: string | null): string {
  if (!raw) return '进行中';
  return GOAL_STATUS_LABELS[raw] ?? raw.toLowerCase();
}

/** 活动颜色 → tailwind 圆点类（对应后端 activityColor：green/orange/purple/blue）。 */
export function activityDotColor(color?: string | null): string {
  switch (color) {
    case 'green':
      return 'bg-[#34c759]';
    case 'orange':
      return 'bg-[#ff9500]';
    case 'purple':
      return 'bg-[#af52de]';
    default:
      return 'bg-[#0a84ff]';
  }
}
