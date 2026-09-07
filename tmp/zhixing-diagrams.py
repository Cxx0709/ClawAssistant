from PIL import Image, ImageDraw, ImageFont
from pathlib import Path
from html import escape
import math

OUT=Path('D:/Temp/ClawAssistant/知行文档配图'); OUT.mkdir(exist_ok=True)
FONT='C:/Windows/Fonts/simsun.ttc'
def diagram(name, boxes, edges, h):
    im=Image.new('RGB',(1500,h),'white'); d=ImageDraw.Draw(im); svg=[]
    def line(points,label=''):
        d.line(points,fill='#63748a',width=3)
        x,y=points[-1]; a,b=points[-2]; t=math.atan2(y-b,x-a)
        tri=[(x,y),(x-15*math.cos(t-.4),y-15*math.sin(t-.4)),(x-15*math.cos(t+.4),y-15*math.sin(t+.4))]
        d.polygon(tri,fill='#63748a'); svg.append('<polyline points="'+' '.join(f'{x},{y}' for x,y in points)+'" fill="none" stroke="#63748a" stroke-width="3" marker-end="url(#a)"/>')
        if label:
            x,y=points[len(points)//2]; x=min(x,1300)
            if label=='取消 / 拒绝': x,y=295,1100
            d.text((x+12,y-30),label,font=ImageFont.truetype(FONT,23),fill='#334155'); svg.append(f'<text x="{x+12}" y="{y-8}" font-size="23">{escape(label)}</text>')
    for pts,label in edges: line(pts,label)
    for x,y,w,hh,txt in boxes:
        d.rounded_rectangle((x,y,x+w,y+hh),radius=14,fill='#f2f6fb',outline='#40658b',width=3)
        svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{hh}" rx="14" fill="#f2f6fb" stroke="#40658b" stroke-width="3"/>')
        lines=txt.split('\n'); size=31; font=ImageFont.truetype(FONT,size)
        while max(d.textlength(t,font=font) for t in lines)>w-28:
            size-=1; font=ImageFont.truetype(FONT,size)
        for i,t in enumerate(lines):
            yy=y+hh/2+(i-(len(lines)-1)/2)*43
            d.text((x+w/2,yy),t,font=font,fill='#172c46',anchor='mm')
            svg.append(f'<text x="{x+w/2}" y="{yy+10}" text-anchor="middle" font-size="{size}">{escape(t)}</text>')
    im.save(OUT/(name+'.png'))
    (OUT/(name+'.svg')).write_text('<svg xmlns="http://www.w3.org/2000/svg" width="1500" height="'+str(h)+'" style="font-family:SimSun,serif"><defs><marker id="a" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto"><path d="M0,0 L0,6 L9,3 z" fill="#63748a"/></marker></defs><rect width="100%" height="100%" fill="white"/>'+''.join(svg)+'</svg>',encoding='utf-8')

diagram('知行技术架构图',[
(230,20,1040,120,'知行浏览器工作台\nReact · TypeScript · 对话 / 附件 / 记忆 / 任务 / 角色'),
(230,200,1040,110,'Spring Security 与 Web Controllers\nSession / CSRF · REST / SSE · 当前用户身份'),
(70,380,420,150,'ChatApplicationService\n附件解析与上下文入口\n会话归属 / ArtifactCollector'),
(540,380,420,150,'ReActAgentExecutor\nSkillRouter / 提示词组装\nExecutionLoop / ToolExecutor'),
(1010,380,420,150,'任务与通知服务\n定时调度 / outbox 写入\n用户级 SSE / 可选邮件'),
(70,620,420,140,'多模态与业务能力\n视觉 / ASR / TTS / 图像\n文档解析 / 天气 / 地图 / 搜索'),
(540,620,420,140,'上下文与能力配置\n历史 / 摘要 / 计划 / 记忆\nSkill YAML / 专项提示词'),
(1010,620,420,140,'外部服务与模型\nQwen / DashScope\n天气 / 地图 / 搜索 API'),
(70,860,420,120,'SQLite\n账户 / 会话 / 计划 / 通知'),
(540,860,420,120,'Qdrant 与 Embedding\n长期记忆 / Skill 知识检索'),
(1010,860,420,120,'本地文件存储\n附件 / 生成产物')],
[([(750,140),(750,200)],''), ([(750,310),(750,345),(280,345),(280,380)],''), ([(750,345),(1220,345),(1220,380)],''), ([(490,450),(540,450)],''), ([(750,530),(750,620)],''), ([(280,530),(280,620)],''), ([(960,450),(985,450),(985,590),(1220,590),(1220,620)],''), ([(750,760),(750,860)],''), ([(1220,530),(1455,530),(1455,815),(280,815),(280,860)],''), ([(280,760),(280,795),(1220,795),(1220,860)],'')],1010)

diagram('知行任务执行流程图',[
(400,20,700,85,'用户发送文字及附件'),
(400,160,700,100,'认证与会话归属校验\n创建运行记录并解析附件'),
(400,315,700,115,'Skill 路由与执行模式选择\n显式切换 → 待处理交互 → 规则 / 续接 → 语义路由'),
(400,490,700,105,'组装提示词与上下文\n历史 / 摘要 / 记忆 / 知识 / 工具定义'),
(400,655,700,85,'模型决策：回复 / 工具调用 / 计划'),
(30,820,360,110,'直接回复或执行终止\n汇总已有结果与错误'),
(570,820,530,110,'工具调用与风险检查\n高风险操作默认等待确认'),
(570,1000,530,100,'获准执行 → 工具结果回填\n更新计划与会话状态'),
(30,1150,1070,100,'SSE 返回正文 / 工具轨迹 / 产物\n持久化完成或失败状态')],
[([(750,105),(750,160)],''), ([(750,260),(750,315)],''), ([(750,430),(750,490)],''), ([(750,595),(750,655)],''), ([(400,700),(210,700),(210,820)],'回复 / 失败'), ([(835,740),(835,820)],'调用工具'), ([(835,930),(835,1000)],'确认或自动放行'), ([(1100,1050),(1350,1050),(1350,697),(1100,697)],'继续下一轮'), ([(210,930),(210,1150)],'结束'), ([(570,875),(470,875),(470,1100),(310,1100),(310,1150)],'取消 / 拒绝')],1280)
