const fs = require('fs'), zlib = require('zlib');
let fail = 0;
const ok = (cond, msg) => { console.log((cond ? '  OK   ' : '  FAIL ') + msg); if (!cond) fail++; };

// --- dati -------------------------------------------------------------------
const idx = JSON.parse(fs.readFileSync('site/data/index.json', 'utf8'));
ok(idx.leagues.length === 5, 'index.json: 5 season');
ok(idx.leagues.every(l => l.short && l.patch && l.start && l.end), 'ogni season ha short, patch, date');
ok(idx.leagues.map(l => l.patch).join(',') === '0.1,0.2,0.3,0.4,0.5', 'patch 0.1-0.5 in ordine');
ok(idx.currentLeague && idx.currentLeague.current === true, 'currentLeague valorizzata');
ok(idx.days.length === 14 && idx.horizons.length === 3, 'griglia giorni/orizzonti completa');
ok(idx.itemScope === 'current-patch', 'filtro patch corrente attivo');
ok(!/Standard|Hardcore/.test(JSON.stringify(idx)), 'nessun riferimento a leghe permanenti');

const si = JSON.parse(fs.readFileSync('site/data/series/_index.json', 'utf8'));
const totItems = si.categories.reduce((n, c) => n + c.items, 0);
ok(si.categories.length === 17, '17 categorie di serie');
ok(totItems === 643, '643 item, coerenti col filtro patch corrente');

const quotes = JSON.parse(fs.readFileSync('site/data/series/_quotes.json', 'utf8'));
ok(!!quotes.divine, '_quotes contiene il Divine');
const shorts = new Set(idx.leagues.map(l => l.short));
let seriesShorts = new Set(), items = 0, nullFrom = 0;
for (const c of si.categories) {
  for (const it of JSON.parse(fs.readFileSync('site/data/series/' + c.file, 'utf8')).items) {
    items++;
    for (const [l, s] of Object.entries(it.series)) {
      seriesShorts.add(l);
      if (typeof s.from !== 'number' || !Array.isArray(s.p) || !Array.isArray(s.v)) nullFrom++;
      if (s.p.length !== s.v.length) nullFrom++;
    }
  }
}
ok(items === totItems, 'item nei file = item nell indice');
ok([...seriesShorts].every(s => shorts.has(s)), 'gli short delle serie esistono in index.json');
ok(nullFrom === 0, 'ogni serie ha from numerico e p/v della stessa lunghezza');

// --- peso -------------------------------------------------------------------
const walk = d => fs.readdirSync(d, { withFileTypes: true })
  .flatMap(e => e.isDirectory() ? walk(d + '/' + e.name) : [d + '/' + e.name]);
const files = walk('site');
let raw = 0, br = 0;
for (const f of files) { const b = fs.readFileSync(f); raw += b.length; br += zlib.brotliCompressSync(b).length; }
console.log('  ---   ' + files.length + ' file, ' + (raw / 1048576).toFixed(2) + ' MB grezzi, '
  + (br / 1024).toFixed(0) + ' KB brotli');
ok(!files.some(f => /d\d+_h\d+\.json$/.test(f)), 'nessun file di finestra nel deploy');
ok(files.includes('site/_headers') && files.includes('site/.nojekyll'), '_headers e .nojekyll presenti');

// --- pagina -----------------------------------------------------------------
const html = fs.readFileSync('site/index.html', 'utf8');
const js = html.match(/<script>([\s\S]*?)<\/script>/)[1];
try { new Function(js); ok(true, 'JS senza errori di sintassi (' + js.split('\n').length + ' righe)'); }
catch (e) { ok(false, 'JS: ' + e.message); }

const count = (src) => (js.match(new RegExp(src, 'g')) || []).length;
const defs = [...js.matchAll(/^(?:async )?function (\w+)/gm)].map(m => m[1]);
ok(defs.every(d => count('\\b' + d + '\\s*\\(') > 1), 'nessuna funzione morta (' + defs.length + ' definite)');
const ids = [...new Set([...js.matchAll(/\$\('([^']+)'\)/g)].map(m => m[1]))];
const runtime = ['chartbox', 'chart', 'chartName', 'chartMeta', 'legend'];
ok(ids.every(i => html.includes('id="' + i + '"')), 'tutti i ' + ids.length + ' id referenziati esistono');
ok(count('\\bCOLORS\\b') === 0, 'nessun residuo della mappa colori fissa');
ok(/getJson/.test(js) && count('fetch\\(') === 1, 'un solo fetch, dentro getJson');
ok(/og:title/.test(html) && /og:description/.test(html), 'meta Open Graph presenti');
ok(/\[hidden\] \{ display: none !important/.test(html), 'regola [hidden] presente');
ok(/aria-sort/.test(js), 'aria-sort sulle intestazioni');

// --- workflow ---------------------------------------------------------------
const wf = fs.readFileSync('.github/workflows/update.yml', 'utf8');
ok(/POE2SCOUT_CONTACT/.test(wf), 'workflow: usa il secret del contatto');
ok(/permissions:\s*\n\s*contents: write/.test(wf), 'workflow: permessi minimi');
const runLines = wf.split(/\r?\n/).filter(l => !l.trim().startsWith('#')).join('\n');
ok(!/--windows/.test(runLines), 'workflow: nessun comando esporta le finestre');
ok(/workflow_dispatch/.test(wf), 'workflow: lanciabile a mano');

// --- repo -------------------------------------------------------------------
const gi = fs.readFileSync('.gitignore', 'utf8');
ok(/^\/data\/$/m.test(gi) && /^\/out\/$/m.test(gi), '.gitignore esclude /data/ e /out/ ma non site/data');
ok(fs.existsSync('README.md'), 'README presente');

console.log(fail === 0 ? '\nTUTTO OK' : '\n' + fail + ' CONTROLLI FALLITI');
process.exit(fail ? 1 : 0);
