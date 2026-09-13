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
    expect(declarations('.settings__section')).not.toMatch(/border-radius|box-shadow/);
  });

  it('rules the home grid with the same hairlines', () => {
    expect(declarations('.home__grid')).toMatch(/gap:\s*0/);
    expect(declarations('.card--action')).not.toMatch(/border-radius|box-shadow/);
    expect(declarations('.card--action')).toMatch(/border-right:\s*1px solid var\(--separator\)/);
  });
});
