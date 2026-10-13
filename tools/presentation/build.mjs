import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { transform, version } from 'esbuild';

const require = createRequire(import.meta.url);
const root = await realpath(fileURLToPath(new URL('../..', import.meta.url)));
const source = await readFile(require.resolve('@aiden0z/pptx-renderer/browser'));
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const upstreamHash = '46b61afa1435de0c194f93324c9467ca392c891c6e07517727c8ffb5e51c376b';
if (version !== '0.28.2' || sha256(source) !== upstreamHash) {
  throw new Error('Unexpected esbuild version or upstream renderer checksum; restore the pinned lockfile.');
}
const scratchPath = join(root, '.reference', 'tmp');
await mkdir(scratchPath, { recursive: true });
const scratchRoot = await realpath(scratchPath);
if (relative(root, scratchRoot) !== join('.reference', 'tmp')) {
  throw new Error('Presentation build scratch directory must remain inside this checkout.');
}
const temporary = await mkdtemp(join(scratchRoot, 'presentation-build-'));
try {
  const staged = join(temporary, 'renderer.js');
  await writeFile(staged, source);
  execFileSync('git', [
    'apply', '--whitespace=error', '--directory=' + relative(root, temporary).split(sep).join('/'),
    join(root, 'docs', 'third-party', 'pptx-renderer-1.3.0.patch'),
  ], { cwd: root, stdio: 'inherit', windowsHide: true });
  const banner = (await readFile(new URL('./compat.js', import.meta.url), 'utf8')).replace(/\r\n/g, '\n');
  const compiled = await transform(await readFile(staged, 'utf8'), {
    target: 'chrome74',
    format: 'esm',
    legalComments: 'inline',
    charset: 'utf8',
    sourcefile: 'renderer.js',
    banner,
  });
  const asset = join(root, 'app', 'src', 'main', 'assets', 'presentation', 'renderer.js');
  const notice = join(dirname(asset), 'NOTICE.txt');
  const hash = sha256(compiled.code);
  const noticeText = await readFile(notice, 'utf8');
  if (!/^SHA-256: [a-f0-9]{64}$/m.test(noticeText)) throw new Error('Renderer NOTICE checksum field is missing.');
  await writeFile(asset, compiled.code);
  await writeFile(notice, noticeText.replace(/^SHA-256: [a-f0-9]{64}$/m, `SHA-256: ${hash}`));
  console.log(`renderer.js: ${Buffer.byteLength(compiled.code)} bytes, SHA-256 ${hash}`);
} finally {
  if (dirname(await realpath(temporary)) !== scratchRoot) throw new Error('Refusing to clean an unexpected directory.');
  await rm(temporary, { recursive: true, force: true });
}
