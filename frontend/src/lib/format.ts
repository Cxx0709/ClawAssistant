/** 毫秒 → 人类可读耗时：`853` → `0.9s`，`4200` → `4.2s`。 */
export function fmtDuration(ms?: number): string {
  if (ms == null || Number.isNaN(ms) || ms < 0) return '';
  if (ms < 1000) return `${Math.max(1, Math.round(ms / 100)) / 10}s`;
  const s = ms / 1000;
  return `${Math.round(s * 10) / 10}s`;
}

/** 时间戳 → 等宽数字时间：`monoTime(Date.now())` → `14:30` */
export const monoTime = (ts: number) =>
  new Date(ts).toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit' });

/** 时间戳 → 完整等宽时间：`monoTimeFull(Date.now())` → `14:30:25` */
export const monoTimeFull = (ts: number) =>
  new Date(ts).toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });

/** 工具名称 → 友好动作描述（用于执行态说明条） */
const TOOL_ACTION_LABELS: Record<string, string> = {
  // 课表相关
  course_schedule: '查询课表',
  exam_schedule: '查询考试',
  exam_reminder_setup: '设置考试提醒',

  // 天气相关
  weather_query: '查询天气',

  // 旅行相关
  travel_collect: '收集旅行需求',
  travel_save_options: '生成旅行方案',
  travel_select_option: '选择旅行方案',
  travel_revise: '修改旅行方案',
  travel_calculate_cost: '计算旅行费用',

  // 交通相关
  transport_recommend: '推荐交通方式',

  // 地图相关
  map_search_place: '搜索地点',
  map_route_planning: '规划路线',
  map_distance_calculate: '计算距离',
  place_image_search: '搜索地点图片',

  // 图片相关
  image_generate: '生成图片',

  // 文件相关
  file_generate: '生成文件',
  file_save: '保存文件',
  file_read: '读取文件',
  file_search: '搜索文件',
  file_delete: '删除文件',
  file_update: '更新文件',

  // 语音相关
  text_to_speech: '转换语音',

  // 任务相关
  create_schedule_task: '创建定时任务',
  list_schedule_tasks: '查询定时任务',
  update_schedule_task: '更新定时任务',
  cancel_schedule_task: '取消定时任务',

  // 记忆相关
  memory_manage: '管理记忆',

  // 网络搜索
  web_search: '网络搜索',

  // 时间相关
  time_query: '查询时间',
  holiday_check: '查询节假日',

  // 目标相关
  goal_manage: '管理目标',

  // 动漫相关
  anime_recommend: '推荐动漫',
  anime_subscribe: '订阅动漫',
};

/** 工具名称 → 友好动作描述。未知名直接回显工具名。 */
export function toolActionLabel(toolName?: string | null): string {
  if (!toolName) return '执行中';
  return TOOL_ACTION_LABELS[toolName] ?? toolName;
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
