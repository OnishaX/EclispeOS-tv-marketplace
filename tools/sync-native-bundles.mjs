import { readFileSync, writeFileSync, mkdirSync, existsSync, cpSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const android = process.argv[2];
if (!android) throw new Error('Usage: node tools/sync-native-bundles.mjs <Android project>');
const repo = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const main = resolve(android, 'app/src/main');
const entries = [
  ['eclipse-app-music', 'Browse and play local, Jellyfin and Navidrome music.'],
  ['eclipse-app-movies', 'Browse movie posters and play videos with the built-in Eclipse player.'],
  ['eclipse-app-tv-shows', 'Browse show posters, seasons and episodes.'],
  ['eclipse-plugin-connection-jellyfin', 'Connect Eclipse media apps to a Jellyfin server.'],
  ['eclipse-plugin-connection-navidrome', 'Connect Eclipse Music to a Navidrome/Subsonic server.'],
  ['eclipse-app-compatibility-profiler', 'Review hardware and firmware capabilities and propose a model compatibility plugin through GitHub.'],
];
const json = path => JSON.parse(readFileSync(path, 'utf8'));
const saveJson = (path, data) => {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, JSON.stringify(data, null, 2) + '\n');
};
function vectorSvg(xml) {
  const width = xml.match(/android:viewportWidth="([^"]+)"/)?.[1];
  const height = xml.match(/android:viewportHeight="([^"]+)"/)?.[1];
  if (!width || !height) throw new Error('Invalid vector dimensions');
  const attrs = text => Object.fromEntries([...text.matchAll(/android:(\w+)="([^"]*)"/g)].map(([, k, v]) => [k, v]));
  const color = (value, name) => {
    if (!value) return `${name}="none"`;
    if (!/^#[a-fA-F0-9]{6}(?:[a-fA-F0-9]{2})?$/.test(value)) throw new Error('Unsupported vector color');
    return value.length === 9 ? `${name}="#${value.slice(3)}" ${name}-opacity="${parseInt(value.slice(1, 3), 16) / 255}"` :
      `${name}="${value}"`;
  };
  let content = '';
  for (const [tag, name, text] of xml.matchAll(/<(\/?(?:path|group))\b([^>]*)>/g)) {
    if (name === '/group') { content += '</g>'; continue; }
    const a = attrs(text);
    if (name === 'group') {
      if (a.rotation || a.pivotX || a.pivotY) throw new Error('Unsupported vector rotation');
      content += `<g transform="translate(${a.translateX ?? 0} ${a.translateY ?? 0}) scale(${a.scaleX ?? 1} ${a.scaleY ?? 1})">`;
    } else {
      if (!a.pathData) throw new Error(`Missing vector path: ${tag}`);
      content += `<path d="${a.pathData}" ${color(a.fillColor, 'fill')} ${color(a.strokeColor, 'stroke')}` +
        ` stroke-width="${a.strokeWidth ?? 0}" stroke-linecap="${a.strokeLineCap ?? 'butt'}"` +
        ` fill-rule="${a.fillType === 'evenOdd' ? 'evenodd' : 'nonzero'}"/>`;
    }
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}">${content}</svg>\n`;
}
for (const [id, description] of entries) {
  const source = resolve(main, 'assets/bundles', id);
  const target = resolve(repo, 'bundles', id);
  mkdirSync(target, { recursive: true });
  const manifest = json(resolve(source, 'manifest.json'));
  const svg = resolve(target, 'artwork.svg');
  if (!existsSync(svg)) {
    const declared = resolve(source, manifest.icons[0].path);
    let vector = existsSync(declared) ? declared : resolve(main, manifest.icons[0].path);
    let xml = readFileSync(vector, 'utf8');
    const alias = xml.match(/android:drawable="@drawable\/([a-z0-9_]+)"/)?.[1];
    if (alias) {
      if (!/android:inset="0dp"/.test(xml)) throw new Error('Unsupported icon inset');
      vector = resolve(main, `res/drawable/${alias}.xml`);
      xml = readFileSync(vector, 'utf8');
    }
    writeFileSync(svg, vectorSvg(xml));
  }
  execFileSync('magick', ['-background', 'none', svg, '-resize', '512x512', '-gravity', 'center',
    '-extent', '512x512', '-strip', `PNG32:${resolve(target, 'icon0.png')}`]);
  manifest.icons[0].path = 'icon0.png';
  manifest.minimumHostVersionCode = 2;
  saveJson(resolve(target, 'manifest.json'), manifest);
  saveJson(resolve(target, 'marketplace/marketplace-manifest.json'), {
    schemaVersion: 1, description,
    features: id.endsWith('compatibility-profiler') ?
      ['Read-only local ADB scan', 'Review display, sound, OS, remote and buffer facts',
        'QR GitHub Device Flow sign-in', 'Explicitly create a public pull request; never push to main',
        'No raw dumps, network addresses, serial numbers or credentials in reports'] :
      ['Native Eclipse integration', 'Requires Eclipse host version 2 or later'],
  });
  if (existsSync(resolve(source, 'src'))) cpSync(resolve(source, 'src'), resolve(target, 'src'), {
    recursive: true, filter: path => !path.endsWith('.DS_Store'),
  });
}
const compatibility = json(resolve(repo, 'bundles/eclipse-plugin-compatibility-tv-jvc-g24/manifest.json'));
const legacyPath = resolve(repo, 'bundles/eclipse-tv-jvc-g24/manifest.json');
const legacy = json(legacyPath);
legacy.compatibility = compatibility.compatibility;
legacy.version = compatibility.version;
saveJson(legacyPath, legacy);
for (const catalogName of ['repository-v2.json', 'repository.json']) {
  const path = resolve(repo, catalogName);
  const catalog = json(path);
  if (catalogName === 'repository-v2.json') {
    for (const [id] of entries) {
      const manifest = json(resolve(repo, 'bundles', id, 'manifest.json'));
      if (!catalog.bundles.some(entry => entry.id === id)) catalog.bundles.push({
        id, title: manifest.title, type: manifest.type, featured: true,
      });
    }
  }
  for (const entry of catalog.bundles) {
    entry.version = json(resolve(repo, 'bundles', entry.id, 'manifest.json')).version;
  }
  saveJson(path, catalog);
}
