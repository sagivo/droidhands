import { qrTerminal } from './qr.js';

/**
 * The human-readable startup banner (CLI only): the phone setup link and its QR code. It is written
 * to stderr, not through the JSON logger, because it may contain the access key (in the URL
 * fragment) and is not a log line. Pure.
 */
export function setupBanner(url: string, options: { readonly guessed: boolean }): string {
  const indent = (text: string): string =>
    text
      .split('\n')
      .map((line) => `  ${line}`)
      .join('\n');
  const lines = [
    '',
    '  PhoneBridge relay is running.',
    '',
    '  Set up a phone: open this link on it, or scan the QR code with its camera.',
    '',
    `    ${url}`,
    '',
    indent(qrTerminal(url)),
    '',
  ];
  if (options.guessed) {
    lines.push('  PUBLIC_URL is not set, so this address is a guess that works for phones on your network.', '');
  }
  return `${lines.join('\n')}\n`;
}
