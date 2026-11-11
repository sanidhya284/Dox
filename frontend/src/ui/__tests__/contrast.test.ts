import { describe, expect, it } from 'vitest';
import css from '../../styles.css?raw';

/**
 * WCAG AA contrast (4.5:1 for normal text) for every text/background pair the stylesheet uses, in both themes.
 * The colours are read from styles.css itself (dark is the base :root, light the prefers-color-scheme: light
 * override), so a palette change can't silently break contrast.
 */
function tokens(block: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const m of block.matchAll(/--([a-z0-9-]+):\s*(#[0-9a-fA-F]{6})/g)) {
    out[m[1]] = m[2];
  }
  return out;
}

const LIGHT_AT = css.indexOf('@media (prefers-color-scheme: light)');
const dark = tokens(css.slice(css.indexOf(':root {'), LIGHT_AT));
const light = { ...dark, ...tokens(css.slice(LIGHT_AT).split('}')[0]) };

function luminance(hex: string): number {
  const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
}

function ratio(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

const BACKGROUNDS = ['paper', 'surface', 'sunken'];
const PAIRS: [string, string][] = [
  ...BACKGROUNDS.flatMap((bg) => ['ink', 'ink-2', 'accent', 'error'].map((fg) => [fg, bg] as [string, string])),
  // Per-user username colours: on the log (paper), panels and dialogs (surface), a highlighted search result
  // (accent-soft). The bot's nick uses --accent, covered above.
  ...['paper', 'surface', 'accent-soft'].flatMap((bg) => [0, 1, 2, 3, 4, 5]
    .map((n) => [`nick-${n}`, bg] as [string, string])),
  ['ink', 'accent-soft'], ['accent-ink', 'accent'], ['warn-ink', 'warn-bg'], ['accent-ink', 'error'],
];

describe('colour contrast meets WCAG AA (4.5:1)', () => {
  it('found both palettes in styles.css', () => {
    expect(Object.keys(dark).length).toBeGreaterThan(15);
    expect(light.paper).not.toBe(dark.paper);
  });

  for (const [theme, t] of [['dark', dark], ['light', light]] as const) {
    it.each(PAIRS)(`${theme}: %s on %s`, (fg, bg) => {
      expect(ratio(t[fg], t[bg]), `${fg} ${t[fg]} on ${bg} ${t[bg]}`).toBeGreaterThanOrEqual(4.5);
    });
  }
});
