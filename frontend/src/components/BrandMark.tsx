interface BrandMarkProps {
  /** 图标边长（px） */
  size?: number;
  /** true = 绿底白标的「瓷贴」logo；false = 纯图形（继承外层 color） */
  tile?: boolean;
  className?: string;
}

/** 品牌图形：极简蟹标（body dome + 双钳 + 眼柄 + 小步足），24 网格几何化。 */
function CrabGlyph() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" className="h-full w-full">
      {/* 步足 */}
      <g stroke="currentColor" strokeWidth="1.2" strokeLinecap="round">
        <path d="M8.9 15.2 L7.0 15.9" />
        <path d="M9.0 17.1 L7.1 18.1" />
        <path d="M9.2 19.0 L7.6 20.3" />
        <path d="M15.1 15.2 L17.0 15.9" />
        <path d="M15.0 17.1 L16.9 18.1" />
        <path d="M14.8 19.0 L16.4 20.3" />
      </g>
      {/* 身体（壳体） */}
      <path
        fill="currentColor"
        d="M8.9 20 L8.9 12.6 C8.9 9.1 10.1 7.6 12 7.6 C13.9 7.6 15.1 9.1 15.1 12.6 L15.1 20 Z"
      />
      {/* 双钳 */}
      <g fill="currentColor">
        <circle cx="6.8" cy="11.2" r="1.85" />
        <circle cx="5.2" cy="9.6" r="0.95" />
        <circle cx="17.2" cy="11.2" r="1.85" />
        <circle cx="18.8" cy="9.6" r="0.95" />
      </g>
      {/* 眼柄 + 眼珠（最后画，叠在壳体上方） */}
      <g stroke="currentColor" strokeWidth="1.2" strokeLinecap="round">
        <path d="M10.2 7.4 L10.2 5.7" />
        <path d="M13.8 7.4 L13.8 5.7" />
      </g>
      <g fill="currentColor">
        <circle cx="10.2" cy="5.2" r="0.75" />
        <circle cx="13.8" cy="5.2" r="0.75" />
      </g>
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
      {tile ? (
        <div
          className="flex h-full w-full items-center justify-center rounded-[30%] bg-brand text-white shadow-[0_4px_14px_-4px_rgba(16,163,127,.6)]"
        >
          <div style={{ width: size * 0.66, height: size * 0.66 }}>
            <CrabGlyph />
          </div>
        </div>
      ) : (
        <div className="h-full w-full text-brand">
          <CrabGlyph />
        </div>
      )}
    </div>
  );
}
