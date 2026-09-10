import { describe, expect, it } from 'vitest';
import { createLegacyPreferenceSource, type LegacySourceDependencies } from './legacy-prefs-source.js';

const NODE = 'com/androidperformancestudio/desktop';

function dependencies(overrides: Partial<LegacySourceDependencies> = {}): LegacySourceDependencies {
  return {
    platform: 'linux',
    userHome: '/home/user',
    readFile: async () => {
      throw new Error('ENOENT');
    },
    runText: async () => ({ exitCode: 1, stdout: '', stderr: '' }),
    ...overrides,
  };
}

describe('createLegacyPreferenceSource', () => {
  it('reads the Linux prefs.xml for the requested node', async () => {
    const xml = [
      '<preferences EXTERNAL_XML_VERSION="1.0"><root type="user"><map/>',
      '<node name="com"><map/><node name="androidperformancestudio"><map/>',
      '<node name="desktop"><map><entry key="application.theme" value="dark"/></map></node>',
      '</node></node></root></preferences>',
    ].join('');
    const source = createLegacyPreferenceSource(
      dependencies({
        readFile: async (path) => {
          expect(path).toBe('/home/user/.java/.userPrefs/' + NODE + '/prefs.xml');
          return xml;
        },
      }),
    );
    expect((await source.readNode(NODE)).get('application.theme')).toBe('dark');
  });

  it('prefers plutil output on macOS', async () => {
    const plist = [
      '<plist version="1.0"><dict>',
      '<key>/' + NODE + '</key><dict><key>application.language</key><string>english</string></dict>',
      '</dict></plist>',
    ].join('');
    const source = createLegacyPreferenceSource(
      dependencies({
        platform: 'darwin',
        runText: async (executable, args) => {
          expect(executable).toBe('plutil');
          expect(args).toEqual(['-convert', 'xml1', '-o', '-', '/home/user/Library/Preferences/com.apple.java.util.prefs.plist']);
          return { exitCode: 0, stdout: plist, stderr: '' };
        },
      }),
    );
    expect((await source.readNode(NODE)).get('application.language')).toBe('english');
  });

  it('queries the Windows registry under JavaSoft\\Prefs', async () => {
    const source = createLegacyPreferenceSource(
      dependencies({
        platform: 'win32',
        runText: async (executable, args) => {
          expect(executable).toBe('reg');
          expect(args).toEqual(['query', 'HKCU\\Software\\JavaSoft\\Prefs\\com\\androidperformancestudio\\desktop']);
          return {
            exitCode: 0,
            stdout: 'HKEY_CURRENT_USER\\Software\\JavaSoft\\Prefs\\com\\androidperformancestudio\\desktop\r\n    application.theme    REG_SZ    light\r\n',
            stderr: '',
          };
        },
      }),
    );
    expect((await source.readNode(NODE)).get('application.theme')).toBe('light');
  });

  it('returns an empty node when the platform store is absent', async () => {
    const source = createLegacyPreferenceSource(dependencies());
    expect((await source.readNode(NODE)).size).toBe(0);
  });
});
