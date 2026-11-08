package scholarchain.er.eval.labeling

/** The single labeling page. All text from the pairs file is inserted with
  * `textContent`, never as markup, because titles can contain HTML.
  */
object LabelPage:
  val html: String =
    """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>ScholarChain pair labeling</title>
<style>
  body { font-family: -apple-system, Helvetica, Arial, sans-serif; margin: 1.5rem auto; max-width: 62rem; color: #1b1b1b; }
  h1 { font-size: 1.1rem; margin: 0 0 .25rem 0; }
  #status, #banner, #message { font-size: .9rem; margin: .25rem 0; }
  #banner { color: #8a4b00; }
  #message { color: #a40000; min-height: 1.2rem; }
  table { border-collapse: collapse; width: 100%; margin-top: .5rem; }
  th, td { border: 1px solid #ccc; padding: .35rem .5rem; vertical-align: top; text-align: left; font-size: .92rem; }
  th.rowname { width: 9rem; background: #f4f4f4; font-weight: 600; }
  .tag { font-size: .75rem; padding: 0 .3rem; border-radius: 3px; margin-left: .4rem; }
  .same { background: #d8f0d8; }
  .diff { background: #f6d6d6; }
  .one { background: #eee; }
  .shared { background: #fff3b0; }
  #keys { margin-top: .8rem; font-size: .85rem; color: #444; }
  #hint { margin: .5rem 0; padding: .4rem .6rem; background: #eef3ff; border: 1px solid #b8c8f0; }
  textarea { width: 100%; box-sizing: border-box; height: 3.2rem; }
</style>
</head>
<body>
<h1>ScholarChain pair labeling</h1>
<div id="banner"></div>
<div id="status"></div>
<div id="message"></div>
<div id="hint" hidden></div>
<div id="pair"></div>
<label for="note">Note (press n to type, Esc to leave the box)</label>
<textarea id="note"></textarea>
<div id="keys">
  1 same &middot; 2 different &middot; 3 unsure &middot;
  <span id="suggestKeys" hidden>s show the hidden opinion &middot; Enter accept the shown opinion &middot;</span>
  n note &middot; Left or p previous &middot; Right or k skip &middot; u first unlabeled
</div>
<script>
const token = new URLSearchParams(location.search).get('t') || '';
let current = null;
let busy = false;
let withSuggestions = false;
let isSubset = false;

async function api(path, body) {
  const options = { headers: { 'X-Label-Token': token } };
  if (body !== undefined) {
    options.method = 'POST';
    options.body = JSON.stringify(body);
    options.headers['Content-Type'] = 'application/json';
  }
  const response = await fetch(path, options);
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || response.statusText);
  return data;
}

function el(tag, text, cls) {
  const node = document.createElement(tag);
  if (text !== undefined && text !== null) node.textContent = text;
  if (cls) node.className = cls;
  return node;
}

function setMessage(text) { document.getElementById('message').textContent = text || ''; }

function cell(values, shared) {
  const td = el('td');
  if (!values || values.length === 0) { td.textContent = '(none)'; return td; }
  values.forEach(value => {
    td.appendChild(el('div', value, shared && shared.has(value.toLowerCase()) ? 'shared' : ''));
  });
  return td;
}

function row(name, left, right) {
  const tr = el('tr');
  tr.appendChild(el('th', name, 'rowname'));
  tr.appendChild(left);
  tr.appendChild(right);
  return tr;
}

function plain(text) {
  const td = el('td');
  td.textContent = (text === null || text === undefined || text === '') ? '(none)' : String(text);
  return td;
}

function identifierRows(left, right) {
  const rows = [];
  const namespaces = [];
  [left, right].forEach(side => side.identifiers.forEach(i => {
    if (!namespaces.includes(i.namespace)) namespaces.push(i.namespace);
  }));
  if (namespaces.length === 0) {
    rows.push(row('Identifiers', plain('(none)'), plain('(none)')));
    return rows;
  }
  namespaces.forEach(ns => {
    const lv = left.identifiers.filter(i => i.namespace === ns).map(i => i.value);
    const rv = right.identifiers.filter(i => i.namespace === ns).map(i => i.value);
    let status = 'one side only', cls = 'one';
    if (lv.length && rv.length) {
      if (lv.some(v => rv.includes(v))) { status = 'SAME'; cls = 'same'; }
      else { status = 'DIFFERENT'; cls = 'diff'; }
    }
    const tr = row('ID: ' + ns, cell(lv), cell(rv));
    tr.firstChild.appendChild(el('span', status, 'tag ' + cls));
    rows.push(tr);
  });
  return rows;
}

function sharedSet(a, b) {
  const set = new Set(a.map(x => x.toLowerCase()));
  const both = new Set();
  b.forEach(x => { if (set.has(x.toLowerCase())) both.add(x.toLowerCase()); });
  return both;
}

function renderPair(data) {
  const table = el('table');
  const head = el('tr');
  head.appendChild(el('th', data.kind + ' pair'));
  head.appendChild(el('th', data.left.record_id));
  head.appendChild(el('th', data.right.record_id));
  table.appendChild(head);
  identifierRows(data.left, data.right).forEach(r => table.appendChild(r));
  const isResearcher = data.kind === 'researcher';
  if (isResearcher) table.appendChild(row('Name', plain(data.left.name), plain(data.right.name)));
  const shared = sharedSet(data.left.coauthors, data.right.coauthors);
  table.appendChild(row(isResearcher ? 'Coauthors' : 'Authors',
    cell(data.left.coauthors, shared), cell(data.right.coauthors, shared)));
  table.appendChild(row('Venue', plain(data.left.venue), plain(data.right.venue)));
  table.appendChild(row('Year', plain(data.left.year), plain(data.right.year)));
  const aff = sharedSet(data.left.affiliations, data.right.affiliations);
  table.appendChild(row('Affiliations', cell(data.left.affiliations, aff), cell(data.right.affiliations, aff)));
  table.appendChild(row('Title', plain(data.left.title), plain(data.right.title)));
  table.appendChild(row('Source', plain(data.left.source), plain(data.right.source)));
  table.appendChild(row('Source record', plain(data.left.source_record_id), plain(data.right.source_record_id)));
  table.appendChild(row('Snapshot', plain(data.left.snapshot_id), plain(data.right.snapshot_id)));
  table.appendChild(row('URI', plain(data.left.source_uri), plain(data.right.source_uri)));
  document.getElementById('pair').replaceChildren(table);
}

function render() {
  const data = current;
  const s = data.stats;
  document.getElementById('status').textContent =
    (data.done ? 'Finished. ' : 'Pair ' + (data.index + 1) + ' of ' + data.total + (isSubset ? ' (subset)' : '') + '. ') +
    'Labeled ' + s.labeled + '/' + s.total + (isSubset ? ' (subset)' : '') + ' (same ' + s.same + ', different ' + s.different +
    ', unsure ' + s.unsure + ').' +
    (withSuggestions ? ' Changed from the outside opinion: ' + s.changed_from_suggestion + '.' : '');
  const hint = document.getElementById('hint');
  const note = document.getElementById('note');
  if (data.done) {
    document.getElementById('pair').replaceChildren(el('p', 'All pairs shown. Press p to go back and revise, or close this page.'));
    hint.hidden = true;
    note.value = '';
    return;
  }
  renderPair(data);
  note.value = data.note || '';
  if (data.suggestion) {
    hint.hidden = false;
    hint.textContent = 'Outside opinion: ' + data.suggestion.label +
      (data.suggestion.reason ? ' (' + data.suggestion.reason + ')' : '') + '. Enter accepts it.';
  } else if (data.has_suggestion) {
    hint.hidden = false;
    hint.textContent = 'An outside opinion exists and is hidden. Press s to show it.';
  } else {
    hint.hidden = true;
  }
  if (data.label) setMessage('Already labeled: ' + data.label + '. Press 1, 2 or 3 to revise.');
}

async function load(index) {
  if (busy) return;
  busy = true;
  try {
    current = await api('/api/pair?i=' + Math.max(0, index));
    setMessage('');
    render();
  } catch (e) { setMessage(e.message); }
  busy = false;
}

async function submit(path, body) {
  if (busy || !current || current.done) return;
  busy = true;
  try {
    await api(path, body);
    busy = false;
    await load(current.index + 1);
  } catch (e) { setMessage(e.message); busy = false; }
}

function noteText() { return document.getElementById('note').value; }

async function setLabel(label) {
  await submit('/api/label', { pair_id: current.pair_id, label: label, note: noteText() });
}

async function reveal() {
  if (busy || !current || current.done || !current.has_suggestion) return;
  try {
    await api('/api/reveal', { pair_id: current.pair_id });
    const keep = noteText();
    await load(current.index);
    document.getElementById('note').value = keep;
  } catch (e) { setMessage(e.message); }
}

document.addEventListener('keydown', event => {
  const note = document.getElementById('note');
  if (event.target === note) {
    if (event.key === 'Escape') { note.blur(); event.preventDefault(); }
    return;
  }
  if (event.metaKey || event.ctrlKey || event.altKey) return;
  switch (event.key) {
    case '1': setLabel('same'); break;
    case '2': setLabel('different'); break;
    case '3': setLabel('unsure'); break;
    case 'Enter':
      if (current && !current.done) {
        if (!current.suggestion) { setMessage(withSuggestions ? 'Nothing is shown. Press s to show an outside opinion, or label with 1, 2 or 3.' : 'Label with 1, 2 or 3.'); }
        else submit('/api/accept', { pair_id: current.pair_id, note: noteText() });
      }
      event.preventDefault(); break;
    case 's': reveal(); break;
    case 'n': note.focus(); event.preventDefault(); break;
    case 'ArrowLeft': case 'p': if (current) load(current.done ? current.total - 1 : current.index - 1); break;
    case 'ArrowRight': case 'k': if (current && !current.done) load(current.index + 1); break;
    case 'u': load(current ? current.first_unlabeled : 0); break;
  }
});

(async function start() {
  try {
    const info = await api('/api/info');
    withSuggestions = !!info.suggestions_file;
    isSubset = !!info.subset;
    document.getElementById('suggestKeys').hidden = !info.suggestions_file;
    const parts = [];
    if (info.subset) parts.push('Blind ' + (info.relabel_pass === 3 ? 'pass 3' : 'second pass') + ' over a subset.');
    if (info.suggestions_file) parts.push('Outside opinions from ' + info.suggestions_file + ' (sha256 ' + info.suggestions_sha256.slice(0, 12) + '...), hidden until you press s.');
    if (info.unknown_suggestion_pair_ids.length) parts.push('Not loaded, pair_id not in the pairs file: ' + info.unknown_suggestion_pair_ids.join(', '));
    parts.push('Reviewer: ' + info.reviewer);
    document.getElementById('banner').textContent = parts.join(' | ');
    await load(info.first_unlabeled);
  } catch (e) { setMessage(e.message); }
})();
</script>
</body>
</html>
"""
