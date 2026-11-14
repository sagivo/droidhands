import { create } from 'qrcode';

/**
 * QR code rendering for the setup link: an SVG path (for the /setup page) and ANSI half-block text
 * (for the startup banner). Encoding is done by the `qrcode` package; rendering is ours so the
 * output is compact and has no inline styles (the pages' CSP forbids them).
 */

/** Light modules around the symbol in the SVG (the QR spec asks for 4). */
const SVG_QUIET_ZONE = 4;
/** Terminals are cramped; 2 modules of explicit white background scan reliably. */
const TERMINAL_QUIET_ZONE = 2;

/** A square module matrix; `dark(row, col)` is false outside the symbol (quiet zone). */
export interface QrMatrix {
  readonly size: number;
  dark(row: number, col: number): boolean;
}

export function qrMatrix(text: string): QrMatrix {
  const { modules } = create(text, { errorCorrectionLevel: 'M' });
  return {
    size: modules.size,
    dark: (row, col) => row >= 0 && col >= 0 && row < modules.size && col < modules.size && modules.get(row, col) === 1,
  };
}

/** SVG drawing of [text]: the viewBox side length and one path of the dark modules (row runs merged). */
export interface QrPath {
  readonly size: number;
  readonly path: string;
}

export function qrPath(text: string): QrPath {
  const matrix = qrMatrix(text);
  const parts: string[] = [];
  for (let row = 0; row < matrix.size; row++) {
    let col = 0;
    while (col < matrix.size) {
      if (!matrix.dark(row, col)) {
        col++;
        continue;
      }
      const start = col;
      while (col < matrix.size && matrix.dark(row, col)) col++;
      parts.push(`M${start + SVG_QUIET_ZONE} ${row + SVG_QUIET_ZONE}h${col - start}v1h-${col - start}z`);
    }
  }
  return { size: matrix.size + 2 * SVG_QUIET_ZONE, path: parts.join('') };
}

/** Standalone `<svg>` markup for [text] (contains no user-controlled text). */
export function qrSvg(text: string, label: string): string {
  const { size, path } = qrPath(text);
  const safeLabel = label.replace(/[&<>"']/g, '');
  return (
    `<svg class="qr" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${size} ${size}" role="img" ` +
    `aria-label="${safeLabel}" shape-rendering="crispEdges"><rect width="${size}" height="${size}" fill="#fff"/>` +
    `<path d="${path}" fill="#000"/></svg>`
  );
}

/**
 * [text] as terminal art: two module rows per line using half blocks, black on an explicit white
 * background (ANSI), so it scans on dark and light terminals alike.
 */
export function qrTerminal(text: string): string {
  const matrix = qrMatrix(text);
  const lo = -TERMINAL_QUIET_ZONE;
  const hi = matrix.size + TERMINAL_QUIET_ZONE;
  const lines: string[] = [];
  for (let row = lo; row < hi; row += 2) {
    let line = '';
    for (let col = lo; col < hi; col++) {
      const top = matrix.dark(row, col);
      const bottom = row + 1 < hi && matrix.dark(row + 1, col);
      line += top && bottom ? '█' : top ? '▀' : bottom ? '▄' : ' ';
    }
    lines.push(`\x1b[30;47m${line}\x1b[0m`);
  }
  return lines.join('\n');
}
