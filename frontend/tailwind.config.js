/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: [
          '-apple-system',
          'BlinkMacSystemFont',
          'Segoe UI',
          'PingFang SC',
          'Hiragino Sans GB',
          'Microsoft YaHei',
          'Helvetica Neue',
          'sans-serif',
        ],
        mono: [
          'ui-monospace',
          'SF Mono',
          'SFMono-Regular',
          'JetBrains Mono',
          'Menlo',
          'Consolas',
          'monospace',
        ],
      },
      colors: {
        // ChatGPT 极简调色：中性近黑文字 + hairline 分隔 + 单绿点缀
        ink: { DEFAULT: '#141517', soft: '#5c6470', faint: '#9aa1ac' },
        line: { DEFAULT: '#e7e9ec', soft: '#eef0f3' },
        canvas: { DEFAULT: '#ffffff', sub: '#fafafb' },
        brand: { DEFAULT: '#5B5BD6', deep: '#4540b0', dim: '#EDEDFB' },  // 紫色（Agent动作与身份）
        ok: { DEFAULT: '#1E9E7A', dim: '#E3F4EE' },                     // 绿色（完成/新增/正常）
        warn: { DEFAULT: '#B97A14', dim: '#FBF0DB' },                   // 琥珀色（待用户决策）
        bubble: '#eef0f2',
      },
      borderRadius: {
        '2.5xl': '1.25rem',
      },
      boxShadow: {
        composer: '0 0 0 1px rgba(20,21,23,.05), 0 4px 20px rgba(20,21,23,.05)',
        pop: '0 10px 40px -12px rgba(20,21,23,.18)',
      },
      keyframes: {
        blink: {
          '0%, 80%, 100%': { opacity: '0.25' },
          '40%': { opacity: '1' },
        },
        fadeup: {
          from: { opacity: '0', transform: 'translateY(6px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
        spinline: {
          from: { transform: 'rotate(0deg)' },
          to: { transform: 'rotate(360deg)' },
        },
      },
      animation: {
        blink: 'blink 1.3s infinite both',
        fadeup: 'fadeup .3s ease-out both',
        spinline: 'spinline .8s linear infinite',
      },
    },
  },
  plugins: [],
};
