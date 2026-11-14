/**
 * Text hygiene for names that come from untrusted parties (OAuth client names, device names) and
 * are shown to the user on the phone or in the relay's pages. Pure.
 */

/** Control, line/paragraph-separator, zero-width, bidi-override and BOM characters. */
const INVISIBLE_OR_CONTROL = /[\p{Cc}\p{Zl}\p{Zp}؜​-‏‪-‮⁠-⁩﻿]/gu;

/**
 * Removes characters that could hide or reorder text, collapses whitespace and truncates to
 * [maxLength] code points (never splitting a surrogate pair). Returns `''` when nothing is left.
 * HTML escaping is still the renderer's job.
 */
export function sanitizeDisplayText(value: string, maxLength: number): string {
  const cleaned = value.replace(INVISIBLE_OR_CONTROL, ' ').replace(/\s+/g, ' ').trim();
  return Array.from(cleaned).slice(0, maxLength).join('').trim();
}
