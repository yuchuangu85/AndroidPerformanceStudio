import { describe, expect, it } from 'vitest';
import { parseLinuxPreferencesXml } from './legacy-linux-xml.js';
import { normalizeNodePath, parseMacOsPreferencesPlist } from './legacy-macos-plist.js';
import { decodeJavaWindowsEscapes, parseWindowsRegistryQuery } from './legacy-windows-registry.js';

const LINUX_XML = [
  '<?xml version="1.0" encoding="UTF-8"?>',
  '<!DOCTYPE preferences SYSTEM "http://java.sun.com/dtd/preferences.dtd">',
  '<preferences EXTERNAL_XML_VERSION="1.0">',
  '  <root type="user">',
  '    <map/>',
  '    <node name="com">',
  '      <map/>',
  '      <node name="androidperformancestudio">',
  '        <map/>',
  '        <node name="desktop">',
  '          <map>',
  '            <entry key="application.theme" value="dark"/>',
  '            <entry key="application.language" value="simplified_chinese"/>',
  '            <entry key="archive.snapshotSizeMultiplier" value="3"/>',
  '          </map>',
  '        </node>',
  '        <node name="ai">',
  '          <map>',
  '            <entry key="model" value="gpt-5.6-luna"/>',
  '          </map>',
  '        </node>',
  '      </node>',
  '    </node>',
  '  </root>',
  '</preferences>',
].join('\n');

const MACOS_PLIST = [
  '<?xml version="1.0" encoding="UTF-8"?>',
  '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">',
  '<plist version="1.0">',
  '<dict>',
  '  <key>/com/androidperformancestudio/desktop</key>',
  '  <dict>',
  '    <key>application.theme</key>',
  '    <string>light</string>',
  '    <key>archive.snapshotSizeMultiplier</key>',
  '    <integer>7</integer>',
  '  </dict>',
  '  <key>/com/androidperformancestudio/ai</key>',
  '  <dict>',
  '    <key>model</key>',
  '    <string>gpt-5.6-luna</string>',
  '  </dict>',
  '</dict>',
  '</plist>',
].join('\n');

const WINDOWS_QUERY = [
  'HKEY_CURRENT_USER\\Software\\JavaSoft\\Prefs\\com\\androidperformancestudio\\desktop',
  '    application.theme    REG_SZ    dark',
  '    application.language    REG_SZ    english',
  '    archive.snapshotSizeMultiplier    REG_DWORD    0x7',
  '    ignored    REG_BINARY    DEADBEEF',
].join('\r\n');

describe('linux java.util.prefs XML', () => {
  it('parses entries with their node path and inherits ancestors', () => {
    const nodes = parseLinuxPreferencesXml(LINUX_XML);
    const desktop = nodes.get('/com/androidperformancestudio/desktop');
    expect(desktop?.get('application.theme')).toBe('dark');
    expect(desktop?.get('application.language')).toBe('simplified_chinese');
    expect(desktop?.get('archive.snapshotSizeMultiplier')).toBe('3');
    expect(nodes.get('/com/androidperformancestudio/ai')?.get('model')).toBe('gpt-5.6-luna');
  });
});

describe('macOS java.util.prefs plist', () => {
  it('parses node dicts including integer values', () => {
    const nodes = parseMacOsPreferencesPlist(MACOS_PLIST);
    expect(nodes.get('/com/androidperformancestudio/desktop')?.get('application.theme')).toBe('light');
    expect(nodes.get('/com/androidperformancestudio/desktop')?.get('archive.snapshotSizeMultiplier')).toBe('7');
    expect(nodes.get('/com/androidperformancestudio/ai')?.get('model')).toBe('gpt-5.6-luna');
  });

  it('normalizes node paths', () => {
    expect(normalizeNodePath('com\\androidperformancestudio\\desktop')).toBe('/com/androidperformancestudio/desktop');
    expect(normalizeNodePath('/com/x')).toBe('/com/x');
  });
});

describe('windows java.util.prefs registry', () => {
  it('parses REG_SZ and REG_DWORD values and skips REG_BINARY', () => {
    const values = parseWindowsRegistryQuery(WINDOWS_QUERY);
    expect(values.get('application.theme')).toBe('dark');
    expect(values.get('application.language')).toBe('english');
    expect(values.get('archive.snapshotSizeMultiplier')).toBe('7');
    expect(values.has('ignored')).toBe(false);
  });

  it('decodes Java unicode escapes', () => {
    expect(decodeJavaWindowsEscapes('a\\u4e2db')).toBe('a中b');
  });
});
