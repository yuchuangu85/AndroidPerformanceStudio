import { describe, expect, it } from 'vitest';
// Vite inlines the stylesheet, so the layout contract is testable without node typings.
import styles from './styles.css?raw';

/** The declarations of the first top-level rule for a selector. */
function declarations(selector: string): string {
  const pattern = new RegExp(
    '(?:^|\\n)' + selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*\\{([^}]*)\\}',
  );
  const match = pattern.exec(styles);
  if (match === null) throw new Error(selector + ' has no rule in styles.css');
  return match[1];
}

describe('shell layout', () => {
  it('draws the home feature cards four to a row', () => {
    expect(declarations('.home__grid')).toMatch(/grid-template-columns:\s*repeat\(4,/);
  });

  it('centres the home heading and its tagline over the grid', () => {
    expect(declarations('.home__title')).toMatch(/text-align:\s*center/);
    expect(declarations('.home__subtitle')).toMatch(/text-align:\s*center/);
  });

  it('lays a feature card out as icon and title, then the description', () => {
    expect(declarations('.card--action')).toMatch(/flex-direction:\s*column/);
    expect(declarations('.card__head')).toMatch(/align-items:\s*center/);
    expect(declarations('.card__summary')).toMatch(/font-size:\s*12px/);
  });

  it('keeps no sidebar of its own: the home grid is the navigation surface', () => {
    expect(styles).not.toMatch(/\.sidebar(__|[\s,{:.])/);
    expect(styles).not.toMatch(/\.nav__/);
  });

  it('butts page sections together instead of floating them on cards', () => {
    expect(declarations('.content__body')).toMatch(/padding:\s*0/);
    expect(declarations('.card')).not.toMatch(/border-radius|box-shadow/);
    expect(declarations('.card')).toMatch(/border-bottom:\s*1px solid var\(--separator\)/);
  });

  it('floats the settings sections as cards with an 8px gutter', () => {
    // Settings is the exception to the ruled page: one card per section, 8px
    // around it, so neighbours never share a hairline.
    const section = declarations('.settings__section');
    expect(section).toMatch(/margin:\s*8px/);
    expect(section).toMatch(/border:\s*1px solid var\(--separator\)/);
    expect(section).toMatch(/border-radius:\s*var\(--radius-tile\)/);
    expect(section).toMatch(/box-shadow:\s*var\(--tile-shadow\)/);
    expect(section).not.toMatch(/border-bottom/);
  });

  it('floats the home feature cards again, in a grid with a gutter', () => {
    expect(styles).toMatch(/--radius-tile:/);
    expect(styles).toMatch(/--tile-shadow:/);
    expect(declarations('.home__grid')).toMatch(/gap:\s*14px/);
    expect(declarations('.card--action')).toMatch(/border-radius:\s*var\(--radius-tile\)/);
    expect(declarations('.card--action')).toMatch(/box-shadow:\s*var\(--tile-shadow\)/);
    expect(declarations('.card--action')).not.toMatch(/border-right|border-bottom/);
  });

  it('gives the Layout Inspector the reference header row, not a capture card', () => {
    const header = declarations('.layout-header');
    expect(header).toMatch(/flex:\s*0 0 auto/);
    expect(header).toMatch(/height:\s*40px/);
    expect(header).toMatch(/border-bottom:\s*1px solid var\(--separator\)/);
    expect(declarations('.layout-header__package')).toMatch(/font-family:\s*var\(--font-mono\)/);
  });

  it('keeps the reference geometry of the Layout Inspector header controls', () => {
    expect(declarations('.layout-header__action')).toMatch(/width:\s*56px/);
    expect(declarations('.layout-switch__track')).toMatch(/width:\s*30px/);
    expect(declarations('.layout-switch__track')).toMatch(/height:\s*16px/);
    expect(declarations('.layout-switch__thumb')).toMatch(/width:\s*12px/);
    expect(declarations('.panel-toggle')).toMatch(/width:\s*26px/);
    expect(declarations('.panel-toggle')).toMatch(/height:\s*21px/);
  });

  it('lets the hierarchy scroll sideways instead of truncating its labels', () => {
    expect(declarations('.tree')).toMatch(/overflow:\s*auto/);
    expect(declarations('.tree__spacer')).toMatch(/width:\s*max-content/);
    expect(declarations('.tree__spacer')).toMatch(/min-width:\s*100%/);
    expect(declarations('.tree__label')).not.toMatch(/text-overflow:\s*ellipsis/);
    expect(declarations('.tree__row')).not.toMatch(/position:\s*absolute/);
  });

  it('stops the tree at its last row instead of scrolling into empty space', () => {
    // The virtual window is offset by a top margin, so the spacer has to be a
    // formatting context of its own: a collapsed margin would move the spacer's
    // box down as the list scrolls and let the range grow without bound.
    expect(declarations('.tree__spacer')).toMatch(/display:\s*flow-root/);
  });

  it('keeps the general settings dropdowns at one fixed width', () => {
    // The reference draws General's language and theme as a DropdownSelector of
    // a set width; the port had a full-width button per option instead.
    const select = declarations('.settings__select select');
    expect(select).toMatch(/flex:\s*none/);
    expect(select).toMatch(/width:\s*200px/);
  });

  it('insets every inspector pane by the same 4px', () => {
    // One value for the hierarchy, the canvas, the properties pane and the
    // findings strip: the reference draws each pane's content edge to edge, and
    // the card's 14/16px padding was the only thing holding it back.
    for (const selector of [
      '.layout__pane--hierarchy',
      '.layout__pane--canvas',
      '.layout__pane--details',
      '.findings',
    ]) {
      expect(declarations(selector)).toMatch(/padding:\s*4px/);
    }
  });

  it('draws the hierarchy search bar, its match colours and the row controls', () => {
    // HierarchySearchBar is a 28px row around a 22px field; the match tints and
    // the highlight colour are the reference's ViewerColors search trio.
    expect(declarations('.tree-search')).toMatch(/height:\s*28px/);
    expect(declarations('.tree-search__field')).toMatch(/height:\s*22px/);
    expect(declarations('.tree__row--match')).toMatch(/background:\s*var\(--search-match\)/);
    expect(declarations('.tree__row--current-match')).toMatch(
      /background:\s*var\(--search-current-match\)/,
    );
    expect(declarations('.tree__label-match')).toMatch(/color:\s*var\(--search-highlight\)/);
    expect(declarations('.tree__chevron')).toMatch(/stroke:\s*var\(--accent\)/);
    expect(declarations('.tree__action')).toMatch(/width:\s*28px/);
    expect(declarations('.tree__action')).toMatch(/height:\s*16px/);
    // The field keeps the focus ring on its own border: the input inside it is
    // inset, so a ring around the input would not line up with that border.
    expect(declarations('.tree-search__field input')).toMatch(/height:\s*100%/);
    expect(declarations('.tree-search__field input')).toMatch(/padding:\s*0/);
    expect(declarations('.tree-search__field input:focus')).toMatch(/box-shadow:\s*none/);
    expect(declarations('.tree-search__field:focus-within')).toMatch(/border-color:\s*var\(--accent\)/);
    expect(styles).toMatch(/--search-match:\s*rgba\(10, 132, 255, 0\.2\)/);
    expect(styles).toMatch(/--search-current-match:\s*rgba\(10, 132, 255, 0\.4\)/);
    expect(styles).toMatch(/--search-highlight:\s*#0a84ff/);
    expect(styles).toMatch(/--search-highlight:\s*#64b5ff/);
  });

  it('carries every theme colour and the family derived from it', () => {
    // One block per preset, and one rule that derives the rest of the accent
    // family from the base the preset sets.
    for (const [name, hex] of [
      ['banana-red', '#d4042d'],
      ['warm-sun-orange', '#db7a0e'],
      ['cornflower-blue', '#5a92e5'],
      ['jade-green', '#5e8034'],
      ['merlot-pink', '#eb6d98'],
      ['azure', '#41b5c2'],
      ['lemon-yellow', '#faca2e'],
      ['royal-purple', '#722169'],
    ]) {
      expect(declarations(":root[data-accent='" + name + "']")).toMatch(
        new RegExp('--accent:\\s*' + hex),
      );
    }
    const derived = declarations(":root[data-accent]:not([data-accent='default'])");
    expect(derived).toMatch(/--accent-soft:\s*color-mix\(in srgb, var\(--accent\) 12%, transparent\)/);
    expect(derived).toMatch(/--focus-ring:\s*color-mix\(in srgb, var\(--accent\) 35%, transparent\)/);
    expect(derived).toMatch(/--search-highlight:\s*var\(--accent\)/);
    // The picker paints the presets, not a second colour.
    expect(declarations('.settings__accent')).toMatch(/border-radius:\s*50%/);
    expect(declarations('.settings__accent--selected')).toMatch(/box-shadow:\s*0 0 0 2px var\(--surface\)/);
  });

  it('rounds the app-only preview to half the reference corner', () => {
    expect(declarations('.canvas__stage--app')).toMatch(/border-radius:\s*12px/);
  });

  it('draws the findings list on the canvas viewport sunken surface', () => {
    // CANVAS pairs a card-coloured title row with a sunken content area;
    // FINDINGS draws the same pair instead of one flat panel.
    const list = declarations('.findings__list');
    expect(list).toMatch(/background:\s*var\(--surface-sunken\)/);
    expect(list).toMatch(/border:\s*1px solid var\(--separator\)/);
    expect(declarations('.canvas__viewport')).toMatch(/background:\s*var\(--surface-sunken\)/);
  });

  it('keeps the home gutter on the home body, and on no other body', () => {
    expect(declarations('.content__body--home')).toMatch(/padding:\s*26px 32px 32px/);
    expect(declarations('.content__body')).toMatch(/padding:\s*0/);
  });
});
