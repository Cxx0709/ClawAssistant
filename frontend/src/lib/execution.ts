import type { ChatMsg, ToolItem } from './types';
import { toolActionLabel } from './format';

export type ExecutionState = 'running' | 'completed' | 'failed' | 'cancelled' | 'unknown';

/** 课表预览可能在工具未加载时留下 err，但这不代表用户请求失败。 */
export function isDisplaySuccess(tool: ToolItem): boolean {
  return tool.state === 'ok' || (tool.name === 'course_schedule' && tool.state === 'err');
}

export function executionState(message: ChatMsg): ExecutionState {
  if (message.status === 'FAILED' || message.errorText) return 'failed';
  if (message.status === 'CANCELLED') return 'cancelled';
  if (message.status === 'COMPLETED') return 'completed';
  if (message.streaming || message.status === 'STREAMING') return 'running';
  return 'unknown';
}

export function executionLabel(message: ChatMsg): string {
  switch (executionState(message)) {
    case 'failed': return '执行失败';
    case 'cancelled': return '已取消';
    case 'completed': return message.tools?.some(tool => tool.state === 'err' && !isDisplaySuccess(tool) && tool.name !== 'image_recognition') ? '已完成 · 有工具失败' : '已完成';
    case 'unknown': return '状态未记录';
    case 'running': {
      const active = message.tools?.find(tool => tool.state === 'running');
      return active ? `正在${toolActionLabel(active.name)} · 等待结果` : '正在生成回复';
    }
  }
}

export function toolStateLabel(tool: ToolItem, running: boolean): string {
  if (isDisplaySuccess(tool)) return '已完成';
  if (tool.state === 'err' && tool.name !== 'image_recognition') return '执行失败';
  if (tool.state === 'err') return '已完成'; // image_recognition 实际已由 VisionService 完成
  return running ? '等待结果' : '未完成';
}

export function executionRecords(messages: ChatMsg[]) {
  let request = '';
  const records: { message: ChatMsg; request: string }[] = [];
  for (const message of messages) {
    if (message.role === 'user') request = message.content;
    else records.push({ message, request: request || '执行记录（问题在更早消息中）' });
  }
  return records.reverse();
}
