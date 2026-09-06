interface BrandMarkProps {
  /** 图标边长（px） */
  size?: number;
  /** true = 渐变底白标；false = 渐变纯图形 */
  tile?: boolean;
  className?: string;
}

/** 知行主标：对话气泡 + 向右箭头，表达“思而行”。 */
function ZhixingGlyph() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" className="h-full w-full">
      <defs>
        <linearGradient id="zhixing-gradient" x1="2" y1="3" x2="22" y2="21" gradientUnits="userSpaceOnUse">
          <stop stopColor="#4F46E5" />
          <stop offset="0.55" stopColor="#8B5CF6" />
          <stop offset="1" stopColor="#22D3EE" />
        </linearGradient>
      </defs>
      <path
        fill="url(#zhixing-gradient)"
        d="M4 3.5h16A3.5 3.5 0 0 1 23.5 7v6A3.5 3.5 0 0 1 20 16.5h-6.2L9 20.5v-4H4A3.5 3.5 0 0 1 .5 13V7A3.5 3.5 0 0 1 4 3.5Z"
      />
      <path
        d="M6.25 10h8.3m0 0-3.1-3.1m3.1 3.1-3.1 3.1"
        fill="none"
        stroke="#fff"
        strokeWidth="1.9"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export default function BrandMark({ size = 32, tile = true, className }: BrandMarkProps) {
  return (
    <div
      className={className}
      style={{
        width: size,
        height: size,
        flex: 'none',
      }}
    >
      <div className={tile ? 'h-full w-full drop-shadow-[0_4px_14px_rgba(79,70,229,.25)]' : 'h-full w-full'}>
        <ZhixingGlyph />
      </div>
    </div>
  );
}
