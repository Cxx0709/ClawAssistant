/**
 * Cleans layout noise produced by plain-text document extractors (for example,
 * Apache Tika) without changing meaningful whitespace inside the document.
 */
export function normalizeDocumentPreview(content: string): string {
  return content
    .replace(/\r\n?/g, '\n')
    .replace(/[\u200B-\u200D\u2060\uFEFF]/g, '')
    .split('\n')
    .map((line) => line.replace(/[\t \u00A0]+$/g, ''))
    .join('\n')
    .trim()
    .replace(/\n[\t \u00A0]*\n(?:[\t \u00A0]*\n)+/g, '\n\n');
}
