import { mkdtemp, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { UserDocumentationService } from './user-documentation-service.js';

const services: UserDocumentationService[] = [];
afterEach(async () => {
  await Promise.all(services.map((service) => service.close()));
  services.length = 0;
});

async function documentationRoot(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), 'aps-user-docs-'));
  for (const directory of ['docs-user', 'docs-user-zh']) {
    await mkdir(join(root, directory), { recursive: true });
    await writeFile(join(root, directory, 'index.html'), '<!doctype html><title>' + directory + '</title>');
  }
  return root;
}

describe('UserDocumentationService', () => {
  it('opens the requested bundled language over a loopback origin', async () => {
    const opened: string[] = [];
    const service = new UserDocumentationService({ root: await documentationRoot(), openExternal: async (url) => { opened.push(url); } });
    services.push(service);

    await service.open('zh');
    expect(opened).toHaveLength(1);
    expect(opened[0]).toMatch(/^http:\/\/127\.0\.0\.1:\d+\/docs-user-zh\/$/);
    expect(await (await fetch(opened[0] as string)).text()).toContain('docs-user-zh');
  });

  it('fails closed when a requested bundled site is missing', async () => {
    const root = await mkdtemp(join(tmpdir(), 'aps-user-docs-'));
    const service = new UserDocumentationService({ root, openExternal: async () => undefined });
    services.push(service);

    await expect(service.open('en')).rejects.toThrow('Bundled user documentation is unavailable');
  });
});
