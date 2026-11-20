import { readFileSync, writeFileSync, mkdirSync, existsSync, copyFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const [logos, android] = process.argv.slice(2);
if (!logos || !android) throw new Error('Usage: node tools/update-icons.mjs <SVG directory> <Android project>');
const repo = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const main = resolve(android, 'app/src/main');
const bundles = 'assets/bundles';
const entries = [
  ['hdmi.svg', ['hdmi-1', 'hdmi-2', 'hdmi-3'].map((id, i) => [`eclipse-input-${id}`, `ic_hdmi${i ? i + 1 : ''}`])],
  ['composite.svg', ['', '-2', '-3'].map((id, i) => [`eclipse-input-composite${id}`, `ic_composite${i ? i + 1 : ''}`])],
  ['component.svg', [1, 2, 3].map(i => [`eclipse-input-component-${i}`, `ic_component${i > 1 ? i : ''}`])],
  ['antenna.svg', [['eclipse-input-antenna', 'ic_antenna']]],
  ['cable.svg', [['eclipse-input-cable', 'ic_cable']]],
  ['airplay.svg', [['eclipse-app-airplay', 'ic_airplay']]],
  ['google-cast.svg', [['eclipse-app-google-cast', 'ic_cast']]],
  ['movies.svg', [['eclipse-app-movies', 'ic_movies']]],
  ['TV Shows.svg', [['eclipse-app-tv-shows', 'ic_tv_shows']]],
  ['tv-compatibility.svg', [['eclipse-plugin-compatibility-tv-jvc-g24', 'ic_tv_compatibility']]],
  ['chat.svg', [], 'ic_chat'],
  ['computer.svg', [], 'ic_computer'],
  ['google-play.svg', [], 'logo_google_play'],
];
if (process.argv[4]) entries.push([resolve(process.argv[4]), [['eclipse-app-compatibility-profiler', 'ic_compatibility_profiler']]]);
const save = (path, data) => {
  mkdirSync(dirname(path), { recursive: true });
  if (existsSync(path) && readFileSync(path).equals(Buffer.from(data))) return false;
  writeFileSync(path, data);
  return true;
};
const json = path => JSON.parse(readFileSync(path, 'utf8'));
const bump = manifest => {
  const parts = (manifest.version ?? '0.0').split('.').map(Number);
  if (parts.some(n => !Number.isInteger(n) || n < 0)) throw new Error('Unsupported bundle version');
  parts[parts.length - 1]++;
  manifest.version = parts.join('.');
};
for (const [file, targets, core] of entries) {
  const source = resolve(logos, file);
  const svg = readFileSync(source, 'utf8');
  const size = svg.match(/viewBox="0 0 ([\d.]+) ([\d.]+)"/);
  if (!size || /<(?!svg\b|path\b|\/svg>)[^>]+>/.test(svg)) throw new Error(`Unsupported SVG structure: ${file}`);
  const paths = [...svg.matchAll(/<path\s+([^>]+)\/>/g)].map(([, attrs]) => {
    const d = attrs.match(/\bd="([^"]+)"/)?.[1];
    if (!d || !/\bfill="white"/.test(attrs) || /\b(transform|style|stroke)=/.test(attrs)) {
      throw new Error(`Unsupported SVG path: ${file}`);
    }
    return `        <path android:fillColor="#FFFFFFFF" android:pathData="${d}" />`;
  });
  if (!paths.length) throw new Error(`No SVG paths: ${file}`);
  const [width, height] = size.slice(1).map(Number);
  const side = Math.max(width, height);
  const vector = `<vector xmlns:android="http://schemas.android.com/apk/res/android"\n` +
    `    android:width="48dp" android:height="48dp" android:viewportWidth="${side}" android:viewportHeight="${side}">\n` +
    `    <group android:translateX="${(side - width) / 2}" android:translateY="${(side - height) / 2}">\n` +
    paths.join('\n') + '\n    </group>\n</vector>\n';
  if (core || file === 'tv-compatibility.svg') {
    save(resolve(main, `res/drawable/${core ?? 'ic_tv_compatibility'}.xml`), vector);
  }
  for (const [id, resource] of targets) {
    const local = resolve(main, bundles, id, 'manifest.json');
    if (existsSync(local)) {
      const changed = save(resolve(main, bundles, id, `res/drawable/${resource}.xml`), vector);
      if (changed) {
        const manifest = json(local);
        bump(manifest);
        save(local, JSON.stringify(manifest) + '\n');
      }
    }
    const canonical = resolve(repo, 'bundles', id);
    if (!existsSync(resolve(canonical, 'manifest.json'))) {
      if (!existsSync(local)) throw new Error(`Missing manifest for ${id}`);
      mkdirSync(canonical, { recursive: true });
      const manifest = json(local);
      manifest.icons[0].path = 'icon0.png';
      save(resolve(canonical, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
    }
    const candidates = [canonical];
    const legacy = id === 'eclipse-plugin-compatibility-tv-jvc-g24' ? 'eclipse-tv-jvc-g24' :
      id.replace('eclipse-input-', 'eclipse-').replace('eclipse-app-', 'eclipse-').replace(/hdmi-(\d+)$/, 'hdmi$1');
    if (legacy !== id && existsSync(resolve(repo, 'bundles', legacy, 'manifest.json'))) {
      candidates.push(resolve(repo, 'bundles', legacy));
    }
    for (const folder of candidates) {
      const manifestPath = resolve(folder, 'manifest.json');
      const manifest = json(manifestPath);
      const image = resolve(folder, manifest.icons[0].path);
      const before = existsSync(image) ? readFileSync(image) : null;
      execFileSync('magick', ['-background', 'none', source, '-resize', '512x512', '-gravity', 'center',
        '-extent', '512x512', '-strip', `PNG32:${image}`]);
      if (before && !before.equals(readFileSync(image))) {
        bump(manifest);
        save(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
      }
      copyFileSync(source, resolve(folder, 'artwork.svg'));
    }
  }
}
