'use strict';
/* SpecForge UI: plain JavaScript, no build step, no external requests. */

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));

const LAYERS = [
  ['entities', 'Entities'],
  ['models', 'Models (DTOs)'],
  ['enums', 'Enums'],
  ['repositories', 'Repositories'],
  ['services', 'Services'],
  ['controllers', 'Controllers'],
  ['security', 'Security config'],
  ['exceptions', 'Exceptions'],
  ['project', 'Project files']
];
const NAMING = [
  ['entity', 'Entity class'], ['model', 'DTO class'], ['enumType', 'Enum class'],
  ['repository', 'Repository'], ['service', 'Service'], ['controller', 'Controller']
];
const LAYER_COLOR = {
  entities: '#6366f1', models: '#0891b2', enums: '#d97706', repositories: '#16a34a', services: '#c026d3',
  controllers: '#dc2626', security: '#0f766e', exceptions: '#64748b', project: '#475569'
};
const CONFIG_KEY = 'specforge.config.v1';

const state = {
  info: null, config: null, specId: null, analysis: null, overrides: {}, preview: null,
  selected: null, profile: '', tab: 'paste', uploadedText: '', uploadedName: '', step: 1, filter: ''
};

/* ------------------------------------------------------------------ helpers */

function el(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  if (attrs) for (const [k, v] of Object.entries(attrs)) {
    if (v === false || v == null) continue;
    if (k === 'class') e.className = v;
    else if (k === 'text') e.textContent = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const kid of kids.flat()) if (kid != null) e.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  return e;
}
function icon(id, cls) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', 'ic' + (cls ? ' ' + cls : ''));
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', '#' + id);
  svg.append(use);
  return svg;
}
function getPath(o, p) { return p.split('.').reduce((a, k) => (a == null ? undefined : a[k]), o); }
function setPath(o, p, v) {
  const ks = p.split('.');
  let a = o;
  for (let i = 0; i < ks.length - 1; i++) { if (a[ks[i]] == null) a[ks[i]] = {}; a = a[ks[i]]; }
  a[ks[ks.length - 1]] = v;
}
function mergeDefaults(def, cfg) {
  if (cfg == null || typeof cfg !== 'object' || Array.isArray(cfg)) return structuredClone(def);
  const out = structuredClone(def);
  for (const k of Object.keys(cfg)) {
    if (cfg[k] != null && typeof cfg[k] === 'object' && !Array.isArray(cfg[k]) && typeof def[k] === 'object' && def[k] != null) out[k] = mergeDefaults(def[k], cfg[k]);
    else if (cfg[k] != null) out[k] = cfg[k];
  }
  return out;
}
function slug(s) { return (s || 'api').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'api'; }
function sep(p) { return p.includes('\\') && !p.includes('/') ? '\\' : '/'; }

let toastTimer;
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), 3200);
}
function busy(btn, on) { btn.classList.toggle('busy', on); btn.disabled = on; }

async function api(path, opts = {}) {
  const headers = { 'X-SpecForge': '1' };
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(path, { method: opts.method || 'GET', headers, body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined });
  const ct = res.headers.get('content-type') || '';
  if (!res.ok) {
    let data = null, msg = res.statusText;
    if (ct.includes('json')) { data = await res.json(); msg = data.error || msg; } else { msg = (await res.text()) || msg; }
    const e = new Error(msg); e.status = res.status; e.data = data;
    throw e;
  }
  return ct.includes('zip') ? res.blob() : res.json();
}

function download(blob, name) {
  const a = el('a', { href: URL.createObjectURL(blob), download: name });
  document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 4000);
}

function askName(title, label, value) {
  return new Promise(resolve => {
    const dlg = $('#nameDialog');
    $('#nameTitle').textContent = title;
    $('#nameLabel').textContent = label;
    const input = $('#nameInput');
    input.value = value || '';
    let result = null;
    const done = v => { result = v; dlg.close(); };
    $('#nameForm').onsubmit = e => { e.preventDefault(); done(input.value.trim() || null); };
    $('#nameCancel').onclick = () => done(null);
    dlg.onclose = () => resolve(result);
    dlg.showModal();
    input.focus(); input.select();
  });
}

/* ------------------------------------------------------------------ directory chooser */

function chooseDir(initial) {
  return new Promise(resolve => {
    const dlg = $('#dirDialog');
    let current = '', parent = '', home = state.info.userHome || '';
    let result = null;
    const finish = v => { result = v; dlg.close(); };
    async function load(path) {
      try {
        const r = await api('/api/fs/list?path=' + encodeURIComponent(path || ''));
        current = r.path; parent = r.parent; home = r.home || home;
        $('#dirPath').value = r.exists || !path ? r.path : path;
        $('#dirNote').textContent = r.exists || !path ? (r.writable ? 'writable' : 'read-only') : 'does not exist yet, it will be created';
        const list = $('#dirList');
        list.replaceChildren(...r.dirs.map(d => el('li', { onclick: () => load(r.path.replace(/[\\/]$/, '') + sep(r.path) + d) }, icon('i-folder'), d)));
        if (!r.dirs.length) list.replaceChildren(el('li', { class: 'muted' }, '(no sub-folders)'));
      } catch (e) { toast(e.message); }
    }
    $('#dirGo').onclick = () => load($('#dirPath').value);
    $('#dirPath').onkeydown = e => { if (e.key === 'Enter') { e.preventDefault(); load($('#dirPath').value); } };
    $('#dirUp').onclick = () => parent && load(parent);
    $('#dirHome').onclick = () => load(home);
    $('#dirCancel').onclick = () => finish(null);
    $('#dirChoose').onclick = () => finish($('#dirPath').value.trim() || current);
    dlg.onclose = () => resolve(result);
    dlg.showModal();
    load(initial);
  });
}

/* ------------------------------------------------------------------ config & binding */

function saveLocal() {
  try { localStorage.setItem(CONFIG_KEY, JSON.stringify(state.config)); } catch (e) { /* storage unavailable */ }
  showProfileState();
}
function loadLocal() { try { return JSON.parse(localStorage.getItem(CONFIG_KEY) || 'null'); } catch (e) { return null; } }

const PKG_RE = /^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$/;
function validateField(inp) {
  const kind = inp.dataset.kind;
  let bad = false;
  if (kind === 'package') bad = inp.dataset.path === 'basePackage' ? !PKG_RE.test(inp.value) : inp.value !== '' && !PKG_RE.test(inp.value);
  if (kind === 'pattern') bad = !inp.value.includes('{Name}');
  inp.classList.toggle('invalid', bad);
}

// ---------- Code style (STYLE-6, STYLE-7) ----------
/** The preset whose values equal the current style, or '' when the user mixed choices. */
function stylePresetOf(style) {
  const presets = (state.info && state.info.stylePresets) || {};
  for (const [name, p] of Object.entries(presets)) if (Object.keys(p).every(k => style && style[k] === p[k])) return name;
  return '';
}
function applyStylePreset(name) {
  const p = state.info && state.info.stylePresets && state.info.stylePresets[name];
  if (!p) return;
  state.config.style = { ...state.config.style, ...p };
  saveLocal();
  bindFields();
  toast(name === 'MODERN' ? 'Modern style applied; every choice can still be changed.' : 'Back to the style of earlier versions.');
}
/** ARCH-2: the packages one table gets, for the chosen architecture. */
function refreshArchitecture() {
  const out = $('#archPreview');
  if (!out || !state.config) return;
  const base = state.config.basePackage || 'com.example.api';
  const sub = k => { const s = ((state.config.layers || {})[k] || {}).subPackage; return s ? base + '.' + s : base; };
  const arch = state.config.architecture;
  const lines = arch === 'HEXAGONAL'
    ? [base + '.customer.domain.model  Customer (plain Java)',
       base + '.customer.domain.port.in  CustomerUseCases, CustomerRequest, CustomerResponse',
       base + '.customer.domain.port.out  CustomerRepositoryPort',
       base + '.customer.application  CustomerService',
       base + '.customer.adapter.in.web  CustomerController',
       base + '.customer.adapter.out.persistence  CustomerJpaEntity, CustomerPersistenceAdapter, …']
    : arch === 'MODULAR'
    ? [base + '.sales  package-info (module), CustomerController, CustomerRequest, CustomerResponse',
       base + '.sales.internal  Customer, CustomerRepository, CustomerService, CustomerMapper',
       base + '.shared  PageResponse, ValueConversions, errors (open module)']
    : arch === 'FEATURE'
    ? [base + '.customer', '  Customer, CustomerRepository, CustomerService,', '  CustomerMapper, CustomerController, CustomerDto',
       sub('models') + '  PageResponse (shared)']
    : [sub('entities') + '  Customer', sub('repositories') + '  CustomerRepository', sub('services') + '  CustomerService, CustomerMapper',
       sub('controllers') + '  CustomerController', sub('models') + '  CustomerDto, PageResponse'];
  out.textContent = lines.join('\n');
}

function refreshStyle() {
  const current = stylePresetOf(state.config && state.config.style);
  $$('.choice[data-preset]').forEach(c => c.setAttribute('aria-pressed', String(c.dataset.preset === current)));
  const info = $('#stylePresetInfo');
  if (info) info.textContent = current ? '' : 'Your own mix of choices.';
}

function bindFields(root = document) {
  $$('[data-path]', root).forEach(inp => {
    const v = getPath(state.config, inp.dataset.path);
    if (inp.type === 'checkbox') inp.checked = !!v; else inp.value = v ?? '';
    validateField(inp);
    if (inp._bound) return;
    inp._bound = true;
    inp.addEventListener(inp.type === 'checkbox' || inp.tagName === 'SELECT' ? 'change' : 'input', () => {
      setPath(state.config, inp.dataset.path, inp.type === 'checkbox' ? inp.checked : inp.value);
      validateField(inp);
      saveLocal();
      refreshDerived();
      refreshStyle();
      refreshArchitecture();
    });
  });
  $$('[data-browse]', root).forEach(b => {
    if (b._bound) return;
    b._bound = true;
    b.addEventListener('click', async () => {
      const path = b.dataset.browse;
      const v = await chooseDir(getPath(state.config, path) || '');
      if (v != null) {
        setPath(state.config, path, v); saveLocal(); bindFields();
        if (path === 'templatesDir') loadTemplates();
      }
    });
  });
  refreshDerived();
  refreshStyle();
  refreshArchitecture();
}

// ---------- Spring Boot and Java pickers (VER-1, VER-2) ----------
function bootLines() { return (state.spring && state.spring.lines) || []; }
function minorOf(v) { return String(v || '').split('.').slice(0, 2).join('.'); }

function fillBootPickers() {
  const sel = $('#bootSelect');
  if (!sel) return;
  sel.innerHTML = '';
  for (const l of bootLines()) sel.append(el('option', { value: l.version }, l.version + (l.suggested ? ' (suggested)' : '')));
  sel.append(el('option', { value: '__other' }, 'Other…'));
  if (!sel._bound) {
    sel._bound = true;
    sel.addEventListener('change', () => {
      state.bootOther = sel.value === '__other';
      if (!state.bootOther) setPath(state.config, 'options.springBootVersion', sel.value);
      saveLocal();
      syncBootPicker();
      if (state.bootOther) $('#bootOther').focus();
    });
  }
  syncBootPicker();
}

function syncBootPicker() {
  const sel = $('#bootSelect');
  if (!sel || !state.config) return;
  const v = getPath(state.config, 'options.springBootVersion') || '';
  const line = bootLines().find(l => l.version === v);
  const other = state.bootOther || !line;
  sel.value = other ? '__other' : v;
  $('#bootOther').hidden = !other;
  const sameLine = bootLines().some(l => minorOf(l.version) === minorOf(v));
  $('#bootHint').textContent = !v ? ''
    : line ? (line.suggested ? 'Suggested by start.spring.io.' : line.origin === 'maven-central' ? 'Older line, still supported by the templates.' : 'Offered by start.spring.io.')
    : sameLine ? 'Library coordinates of the same ' + minorOf(v) + '.x line are used.'
    : 'Outside the versions the templates are tested with ' + ((state.spring && state.spring.testedRange) || '') + '; the generated code may not compile.';
  const jsel = $('#javaSelect');
  const useLine = line || bootLines().find(l => minorOf(l.version) === minorOf(v)) || bootLines().find(l => l.version.split('.')[0] === v.split('.')[0]) || bootLines()[0];
  const javas = useLine ? useLine.javaVersions : [];
  const cur = String(getPath(state.config, 'options.javaVersion') || '');
  jsel.innerHTML = '';
  for (const j of javas) jsel.append(el('option', { value: j }, 'Java ' + j + (j === (state.spring && state.spring.javaDefault) ? ' (suggested)' : '')));
  if (cur && !javas.includes(cur)) jsel.append(el('option', { value: cur }, 'Java ' + cur + ' (not offered for Spring Boot ' + v + ')'));
  jsel.value = cur;
}

function refreshDerived() {
  syncBootPicker();
  const proxy = (state.config.options || {}).serviceMode === 'PROXY';
  $$('[data-show-when="proxy"]').forEach(e => { e.hidden = !proxy; });
  const contract = getPath(state.config, 'contract.kind') === 'ADMIN_TEMPLATE';
  $$('[data-show-when="contract"]').forEach(e => { e.hidden = !contract; });
  $$('[data-hide-when="proxy"]').forEach(e => { e.hidden = proxy || (e.dataset.flow === 'spec' && state.source === 'db'); });
  const up = $('#upstreamUrl');
  if (up) up.placeholder = (state.analysis && state.analysis.upstreamUrl) || 'from the spec';
  const base = state.config.basePackage || '';
  LAYERS.forEach(([k]) => {
    const out = $('#pkg-' + k);
    if (!out) return;
    const sub = (state.config.layers[k] || {}).subPackage || '';
    out.textContent = base + (sub ? '.' + sub : '');
  });
  NAMING.forEach(([k]) => {
    const out = $('#ex-' + k);
    if (out) out.textContent = '→ ' + (state.config.naming[k] || '').replace('{Name}', k === 'repository' || k === 'service' || k === 'controller' ? 'Pet' : 'Pet');
  });
}

function buildLayersTable() {
  const body = $('#layersBody');
  body.replaceChildren(...LAYERS.map(([k, label]) => el('tr', null,
    el('td', null, label),
    el('td', null, el('input', { type: 'checkbox', 'data-path': `layers.${k}.enabled`, 'aria-label': 'Generate ' + label })),
    el('td', null, el('input', { type: 'text', 'data-path': `layers.${k}.subPackage`, 'data-kind': 'package', spellcheck: 'false', placeholder: '(base package)' })),
    el('td', null, el('code', { id: 'pkg-' + k })),
    el('td', null, el('div', { class: 'input-btn' },
      el('input', { type: 'text', 'data-path': `layers.${k}.rootDir`, spellcheck: 'false', placeholder: 'same as target directory' }),
      el('button', { type: 'button', class: 'btn small', 'data-browse': `layers.${k}.rootDir` }, '…'))))));
}

function buildNaming() {
  $('#namingGrid').replaceChildren(...NAMING.map(([k, label]) => el('label', { class: 'field' },
    el('span', null, label),
    el('input', { type: 'text', 'data-path': `naming.${k}`, 'data-kind': 'pattern', spellcheck: 'false' }),
    el('small', { class: 'muted', id: 'ex-' + k }))));
}

/* ------------------------------------------------------------------ profiles */

/** UIX-1: the menu button shows the profile's name and a dot while the settings differ from what was saved. */
function showProfileState() {
  const btn = $('#btnProfileMenu');
  if (!btn) return;
  const dirty = !!state.profile && state.savedConfig !== JSON.stringify(state.config);
  $('#profileName').textContent = state.profile || 'No profile';
  $('#profileDirty').hidden = !dirty;
  btn.title = state.profile ? (dirty ? 'Profile "' + state.profile + '": unsaved changes' : 'Profile "' + state.profile + '"') : 'Settings are not saved as a profile';
  $('#btnProfileDelete').disabled = !state.profile;
}

function markProfileSaved() {
  state.savedConfig = JSON.stringify(state.config);
  showProfileState();
}

function openProfileMenu(open, focusFirst) {
  const menu = $('#profileMenu');
  menu.hidden = !open;
  $('#btnProfileMenu').setAttribute('aria-expanded', String(open));
  if (open && focusFirst) (menu.querySelector('[role^=menuitem]:not(:disabled)') || menu).focus();
}

function menuKeys(ev) {
  const items = [...$$('#profileMenu [role^=menuitem]:not(:disabled)')];
  const i = items.indexOf(document.activeElement);
  if (ev.key === 'Escape') { openProfileMenu(false); $('#btnProfileMenu').focus(); ev.preventDefault(); }
  else if (ev.key === 'ArrowDown') { items[(i + 1) % items.length].focus(); ev.preventDefault(); }
  else if (ev.key === 'ArrowUp') { items[(i - 1 + items.length) % items.length].focus(); ev.preventDefault(); }
  else if (ev.key === 'Home') { items[0].focus(); ev.preventDefault(); }
  else if (ev.key === 'End') { items[items.length - 1].focus(); ev.preventDefault(); }
  else if (ev.key === 'Tab') openProfileMenu(false);
}

async function refreshProfiles(select) {
  try {
    const r = await api('/api/profiles');
    state.profile = select !== undefined ? select : (r.profiles.includes(state.profile) ? state.profile : '');
    const list = $('#profileList');
    list.replaceChildren(...(r.profiles.length ? r.profiles.map(n => el('button', {
      type: 'button', role: 'menuitemradio', 'aria-checked': String(n === state.profile),
      onclick: () => { openProfileMenu(false); profileLoad(n); }
    }, n)) : [el('p', { class: 'menu-empty' }, 'No saved profiles yet')]));
    showProfileState();
  } catch (e) { toast(e.message); }
}

async function profileSave(asNew) {
  let name = state.profile;
  if (asNew || !name) {
    name = await askName('Save profile', 'Profile name', name || 'my-profile');
    if (!name) return;
  }
  try {
    await api('/api/profiles/' + encodeURIComponent(name), { method: 'PUT', body: state.config });
    await refreshProfiles(name);
    markProfileSaved();
    toast(`Profile “${name}” saved`);
  } catch (e) { toast(e.message); }
}

async function profileLoad(name) {
  if (!name) { state.profile = ''; return; }
  try {
    const cfg = await api('/api/profiles/' + encodeURIComponent(name));
    state.config = mergeDefaults(state.info.defaults, cfg);
    state.profile = name;
    saveLocal(); bindFields(); loadTemplates();
    markProfileSaved();
    await refreshProfiles(name);
    toast(`Profile “${name}” loaded`);
  } catch (e) { toast(e.message); }
  showProfileState();
}

async function profileDelete() {
  if (!state.profile) return toast('Select a saved profile first');
  if (!confirm(`Delete profile “${state.profile}”?`)) return;
  try { await api('/api/profiles/' + encodeURIComponent(state.profile), { method: 'DELETE' }); toast('Profile deleted'); await refreshProfiles(''); } catch (e) { toast(e.message); }
}

/* ------------------------------------------------------------------ navigation */

const STEP_SECTIONS = { '1': 'step1', select: 'stepSelect', map: 'stepMap', api: 'stepApi', '2': 'step2', '3': 'step3' };
const STEP_BUTTONS = { '1': 'stepBtn1', select: 'stepBtnSelect', map: 'stepBtnMap', api: 'stepBtnApi', '2': 'stepBtn2', '3': 'stepBtn3' };
function flowSteps() { return state.source === 'db' ? ['1', 'select', 'map', 'api', '2', '3'] : ['1', '2', '3']; }
function sourceReady() { return state.source === 'db' ? !!(state.db && state.db.planView) : !!state.specId; }

function goStep(n) {
  n = String(n);
  state.step = n;
  const order = flowSteps();
  for (const [key, id] of Object.entries(STEP_SECTIONS)) $('#' + id).hidden = key !== n;
  order.forEach((key, i) => {
    const b = $('#' + STEP_BUTTONS[key]);
    b.querySelector('.n').textContent = i + 1;
    b.classList.toggle('is-active', key === n);
    b.classList.toggle('is-done', order.indexOf(key) < order.indexOf(n) && sourceReady());
    if (key === n) b.setAttribute('aria-current', 'step'); else b.removeAttribute('aria-current');
  });
  window.scrollTo({ top: 0 });
  // on narrow screens the step bar scrolls: keep the current step visible
  const current = $('#' + STEP_BUTTONS[n]);
  if (current && current.scrollIntoView) current.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  if (n === '2') loadTemplates();
  if (n === '3') runPreview();
  if (n === 'select') renderSelect();
  if (n === 'map') renderMap();
  if (n === 'api') renderApi();
}
function enableSteps() {
  const on = sourceReady();
  for (const key of flowSteps()) if (key !== '1') $('#' + STEP_BUTTONS[key]).disabled = !on;
}

/* ------------------------------------------------------------------ step 1: spec input & analysis */

const SAMPLE = `openapi: 3.0.3
info:
  title: Pet Store
  version: 1.0.0
servers:
  - url: https://api.example.com/v1
tags:
  - name: pet
paths:
  /pets:
    get:
      tags: [pet]
      operationId: listPets
      responses:
        '200':
          description: All pets
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/Pet' }
    post:
      tags: [pet]
      operationId: addPet
      security:
        - apiKey: []
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/Pet' }
      responses:
        '201':
          description: Created
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Pet' }
  /pets/{petId}:
    parameters:
      - { name: petId, in: path, required: true, schema: { type: integer, format: int64 } }
    get:
      tags: [pet]
      operationId: getPet
      responses:
        '200':
          description: The pet
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Pet' }
    delete:
      tags: [pet]
      operationId: deletePet
      security:
        - oauth: [write:pets]
      responses:
        '204': { description: Deleted }
components:
  securitySchemes:
    apiKey: { type: apiKey, in: header, name: X-API-KEY }
    oauth:
      type: oauth2
      flows:
        clientCredentials:
          tokenUrl: https://auth.example.com/token
          scopes: { 'write:pets': modify pets }
  schemas:
    Pet:
      type: object
      required: [name]
      properties:
        id: { type: integer, format: int64 }
        name: { type: string, maxLength: 80 }
        status: { type: string, enum: [available, pending, sold] }
        birthDate: { type: string, format: date }
        tags:
          type: array
          items: { $ref: '#/components/schemas/Tag' }
    Tag:
      type: object
      properties:
        id: { type: integer, format: int64 }
        label: { type: string }
`;

function setTab(name) {
  state.tab = name;
  $$('.tab').forEach(t => t.classList.toggle('is-active', t.dataset.tab === name));
  ['paste', 'upload', 'url'].forEach(t => { $('#tab-' + t).hidden = t !== name; });
}

async function readFile(file) {
  if (!file) return;
  if (file.size > 25 * 1024 * 1024) { toast('That file is larger than 25 MB'); return; }
  state.uploadedText = await file.text();
  state.uploadedName = file.name;
  $('#fileInfo').textContent = `${file.name} (${Math.ceil(file.size / 1024)} KB) ready`;
}

function showAnalysisError(e) {
  const box = $('#analysisError');
  const lines = [e.message];
  if (e.data && Array.isArray(e.data.messages)) e.data.messages.forEach(m => { if (m.text !== e.message) lines.push('• ' + m.text); });
  box.textContent = lines.join('\n');
  box.hidden = false;
  $('#analysis').hidden = true;
}

async function analyze() {
  const btn = $('#btnAnalyze');
  let body;
  if (state.tab === 'paste') body = { mode: 'text', content: $('#specText').value, name: 'pasted' };
  else if (state.tab === 'upload') {
    if (!state.uploadedText) { toast('Choose a file first'); return; }
    body = { mode: 'text', content: state.uploadedText, name: state.uploadedName };
  } else body = { mode: 'url', url: $('#specUrl').value.trim() };
  busy(btn, true);
  $('#analysisError').hidden = true;
  try {
    const a = await api('/api/spec/analyze', { method: 'POST', body });
    state.analysis = a; state.specId = a.specId; state.overrides = {}; state.preview = null;
    renderAnalysis();
    enableSteps();
  } catch (e) {
    state.specId = null; enableSteps();
    showAnalysisError(e);
  } finally { busy(btn, false); }
}

function msgList(ul, msgs) {
  ul.replaceChildren(...msgs.map(m => el('li', { class: m.level }, icon(m.level === 'ERROR' ? 'i-err' : m.level === 'WARN' ? 'i-warn' : 'i-info'), el('span', null, m.text))));
}

function renderAnalysis() {
  const a = state.analysis;
  $('#analysis').hidden = false;
  $('#aTitle').textContent = `${a.title}  ·  v${a.version}`;
  $('#aMeta').textContent = [a.declared, a.format, a.basePath ? 'base path ' + a.basePath : null, a.source && a.source !== 'pasted' ? a.source : null].filter(Boolean).join('  ·  ');
  const stats = [['Operations', a.stats.operations], ['Resources', a.stats.resources], ['Entities', a.stats.entities], ['DTOs', a.stats.dtos], ['Enums', a.stats.enums]];
  $('#statCards').replaceChildren(...stats.map(([l, v]) => el('div', { class: 'stat' }, el('b', null, v), el('span', null, l))));
  $('#messagesCard').hidden = !a.messages.length;
  msgList($('#messages'), a.messages);

  $('#resources').replaceChildren(...a.resources.map(r => el('details', null,
    el('summary', null, el('span', null, r.name), r.entity ? el('span', { class: 'chip info' }, '→ ' + r.entity) : null, el('span', { class: 'count' }, `${r.endpoints.length} endpoint${r.endpoints.length === 1 ? '' : 's'}`)),
    el('table', null, ...r.endpoints.map(e => el('tr', null,
      el('td', null, el('span', { class: 'verb ' + e.method }, e.method)),
      el('td', null, el('code', null, e.path)),
      el('td', { class: 'kind' }, e.kind === 'CUSTOM' ? 'stub' : e.kind.toLowerCase()),
      el('td', null, e.access && e.access !== 'permitAll()' ? el('span', { class: 'lock' }, 'secured') : null)))))));
  if (!a.resources.length) $('#resources').replaceChildren(el('p', { class: 'muted' }, 'No operations found.'));

  $('#schemasBody').replaceChildren(...a.schemas.map(s => {
    let cell;
    if (s.kind === 'ENUM' || s.synthetic) cell = el('span', { class: 'chip' }, s.kind === 'ENUM' ? 'enum' : 'DTO (inline)');
    else {
      const sel = el('select', { 'aria-label': 'Treat ' + s.name + ' as' },
        el('option', { value: 'ENTITY' }, 'Entity'), el('option', { value: 'DTO' }, 'DTO'));
      sel.value = state.overrides[s.name] || s.kind;
      sel.onchange = () => {
        if (sel.value === s.kind) delete state.overrides[s.name]; else state.overrides[s.name] = sel.value;
        why.textContent = sel.value === s.kind ? s.reason : 'changed by you';
        state.preview = null;
      };
      cell = sel;
    }
    const why = el('div', { class: 'why' }, s.reason);
    return el('tr', null, el('td', null, el('div', null, s.name), why), el('td', null, cell), el('td', { class: 'num' }, s.fields));
  }));

  $('#securityHead').hidden = !a.security.length;
  $('#securityList').replaceChildren(...a.security.map(s => el('span', { class: 'chip ok', title: s.detail }, `${s.name} · ${s.type.toLowerCase().replace('_', ' ')}`)));
}

/* ------------------------------------------------------------------ step 2: templates list */

async function loadTemplates() {
  try {
    const r = await api('/api/templates?dir=' + encodeURIComponent(state.config.templatesDir || ''));
    $('#templatesInfo').textContent = r.externalDir
      ? `Using external templates from ${r.externalDir}. Files you have not copied there still come from the JAR.`
      : `Using the templates bundled in the JAR. “Export default templates…” copies them to a folder you can edit (default ${r.defaultDir}, which is then picked up automatically).`;
    $('#templatesBody').replaceChildren(...r.templates.map(t => el('tr', null,
      el('td', null, el('code', null, t.template)),
      el('td', null, t.layer),
      el('td', null, el('span', { class: 'chip ' + (t.source === 'EXTERNAL' ? 'warn' : '') }, t.source.toLowerCase())),
      el('td', null, el('button', { type: 'button', class: 'btn small ghost', onclick: () => viewTemplate(t.template) }, 'View')))));
  } catch (e) { $('#templatesInfo').textContent = e.message; }
}

async function viewTemplate(name) {
  try {
    const r = await api(`/api/templates/source?name=${encodeURIComponent(name)}&dir=${encodeURIComponent(state.config.templatesDir || '')}`);
    $('#tplName').textContent = name;
    $('#tplCode').textContent = r.content;
    $('#tplDialog').showModal();
  } catch (e) { toast(e.message); }
}

async function exportTemplates() {
  const suggested = state.config.templatesDir || state.info.defaultTemplatesDir;
  const dir = await askName('Export default templates', 'Folder to copy the templates into (existing files are never overwritten)', suggested);
  if (!dir) return;
  try {
    const r = await api('/api/templates/export', { method: 'POST', body: { dir } });
    if (!state.config.templatesDir && r.dir !== state.info.defaultTemplatesDir) { state.config.templatesDir = r.dir; saveLocal(); bindFields(); }
    toast(`Copied ${r.copied} file${r.copied === 1 ? '' : 's'} to ${r.dir}`);
    loadTemplates();
  } catch (e) { toast(e.message); }
}

/* ------------------------------------------------------------------ step 3: preview, write, zip */

function genBody(extra) {
  const base = state.source === 'db'
    ? { snapshotId: state.db && state.db.snapshotId, plan: state.config.databasePlan, config: state.config }
    : { specId: state.specId, config: state.config, overrides: state.overrides };
  return Object.assign(base, extra || {});
}

async function runPreview() {
  const btn = $('#btnRefresh');
  busy(btn, true);
  $('#previewError').hidden = true;
  try {
    state.preview = await api('/api/generate/preview', { method: 'POST', body: genBody() });
    renderPreview();
  } catch (e) {
    state.preview = null;
    const box = $('#previewError');
    box.textContent = e.message;
    box.hidden = false;
    $('#tree').replaceChildren(); $('#chips').replaceChildren(); $('#previewMessagesWrap').hidden = true;
    $('#viewerPath').textContent = 'No preview'; $('#viewerCode').textContent = ''; $('#viewerBadge').hidden = true;
  } finally { busy(btn, false); }
}

function renderPreview() {
  const p = state.preview;
  const c = p.counts;
  const chips = [el('span', { class: 'chip' }, `${c.total} files`), el('span', { class: 'chip ok' }, `${c.new} new`)];
  if (c.exists) chips.push(el('span', { class: 'chip warn' }, `${c.exists} already exist and differ`));
  if (c.identical) chips.push(el('span', { class: 'chip' }, `${c.identical} identical on disk`));
  const target = state.config.targetDir;
  chips.push(el('span', { class: 'chip info' }, target ? 'target: ' + target : 'no target directory set (ZIP only)'));
  $('#chips').replaceChildren(...chips);
  const wrap = $('#previewMessagesWrap');
  wrap.hidden = !p.messages.length;
  if (p.messages.length) {
    const errors = p.messages.filter(m => m.level !== 'INFO').length;
    $('#previewMessagesSummary').textContent = `${p.messages.length} note${p.messages.length === 1 ? '' : 's'} from the generator${errors ? ` (${errors} warning${errors === 1 ? '' : 's'})` : ''}`;
    msgList($('#previewMessages'), p.messages);
  }
  renderTree();
  const keep = p.files.find(f => f.path === state.selected);
  const first = keep || p.files.find(f => /Controller\.java$/.test(f.path)) || p.files[0];
  if (first) selectFile(first.path); else { $('#viewerPath').textContent = 'Nothing generated'; $('#viewerCode').textContent = ''; $('#viewerBadge').hidden = true; }
}

function renderTree() {
  const q = state.filter.toLowerCase();
  const files = state.preview.files.filter(f => !q || f.path.toLowerCase().includes(q));
  const root = { name: '', children: new Map(), files: [] };
  for (const f of files) {
    const parts = f.path.split('/');
    let n = root;
    for (let i = 0; i < parts.length - 1; i++) {
      if (!n.children.has(parts[i])) n.children.set(parts[i], { name: parts[i], children: new Map(), files: [] });
      n = n.children.get(parts[i]);
    }
    n.files.push({ name: parts[parts.length - 1], file: f });
  }
  const render = node => {
    const ul = el('ul', { role: 'group' });
    [...node.children.values()].sort((a, b) => a.name.localeCompare(b.name)).forEach(child => {
      let cur = child, name = child.name;
      while (!cur.files.length && cur.children.size === 1) { cur = [...cur.children.values()][0]; name += '/' + cur.name; }
      const li = el('li', { class: 'open', role: 'treeitem' });
      const row = el('div', { class: 'node' }, icon('i-chev', 'chev'), icon('i-folder'), name);
      row.onclick = () => li.classList.toggle('open');
      li.append(row, render(cur));
      ul.append(li);
    });
    node.files.sort((a, b) => a.name.localeCompare(b.name)).forEach(({ name, file }) => {
      const row = el('div', { class: 'node' + (file.path === state.selected ? ' sel' : ''), 'data-path': file.path },
        el('span', { class: 'dot', style: 'background:' + (LAYER_COLOR[file.layer] || '#888') }), name,
        file.status !== 'NEW' ? el('span', { class: 'tag ' + file.status }, file.status === 'EXISTS' ? 'exists' : 'same') : null);
      row.onclick = () => selectFile(file.path);
      ul.append(el('li', { role: 'treeitem' }, row));
    });
    return ul;
  };
  $('#tree').replaceChildren(render(root));
}

function selectFile(path) {
  const f = state.preview.files.find(x => x.path === path);
  if (!f) return;
  state.selected = path;
  $$('#tree .node.sel').forEach(n => n.classList.remove('sel'));
  const node = $$('#tree .node').find(n => n.dataset.path === path);
  if (node) node.classList.add('sel');
  $('#viewerPath').textContent = path;
  const badge = $('#viewerBadge');
  badge.hidden = false;
  badge.className = 'badge ' + f.status;
  badge.textContent = f.status === 'NEW' ? 'new file' : f.status === 'EXISTS' ? 'exists on disk, differs' : 'identical on disk';
  highlightInto($('#viewerCode'), f.content, f.language);
  $('#btnCustomizeFile').hidden = !f.id;
}

function highlightInto(code, text, language) {
  const lang = language === 'properties' ? 'ini' : language;
  if (text.length < 300000 && window.hljs && hljs.getLanguage(lang)) {
    code.innerHTML = hljs.highlight(text, { language: lang, ignoreIllegals: true }).value;
  } else code.textContent = text;
}

function listBlock(title, items, hint) {
  if (!items || !items.length) return null;
  return el('div', null, el('strong', null, `${title} (${items.length})`), hint ? el('span', { class: 'muted small-text' }, '  ' + hint) : null, el('div', { class: 'result-list' }, items.join('\n')));
}

function showResult(r) {
  const errs = Object.entries(r.errors || {}).map(([k, v]) => `${k}: ${v}`);
  $('#resultTitle').textContent = errs.length ? 'Finished with problems' : 'Files written';
  const body = $('#resultBody');
  body.replaceChildren(
    el('p', null, 'Target: ', el('code', null, r.targetDir)),
    listBlock('Created', r.created),
    listBlock('Overwritten', r.overwritten),
    listBlock('Backed up', r.backedUp),
    listBlock('Skipped: already exist', r.skipped, 'choose a different “If a file exists” option to replace them'),
    listBlock('Unchanged: identical content', r.unchanged),
    listBlock('Errors', errs));
  if (!r.created.length && !r.overwritten.length && !errs.length) body.prepend(el('p', { class: 'muted' }, 'No file needed to be written.'));
  $('#resultDialog').showModal();
}

async function writeFiles() {
  if (!state.config.targetDir || !state.config.targetDir.trim()) { toast('Set a target directory in Settings first'); return; }
  const policy = $('#overwrite').value;
  const c = state.preview ? state.preview.counts.exists : 0;
  if (policy === 'OVERWRITE' && c > 0 && !confirm(`${c} existing file(s) will be overwritten without a backup. Continue?`)) return;
  const btn = $('#btnWrite');
  busy(btn, true);
  try {
    const r = await api('/api/generate/write', { method: 'POST', body: genBody({ overwrite: policy }) });
    showResult(r);
    runPreview();
  } catch (e) { toast(e.message); } finally { busy(btn, false); }
}

async function downloadZip() {
  const btn = $('#btnZip');
  busy(btn, true);
  try {
    const blob = await api('/api/generate/zip', { method: 'POST', body: genBody() });
    download(blob, slug(state.analysis && state.analysis.title) + '.zip');
  } catch (e) { toast(e.message); } finally { busy(btn, false); }
}

/* ------------------------------------------------------------------ theme */

function applyTheme() {
  let t = null;
  try { t = localStorage.getItem('specforge.theme'); } catch (e) { /* ignore */ }
  if (t) document.documentElement.dataset.theme = t;
}
function toggleTheme() {
  const cur = document.documentElement.dataset.theme || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  const next = cur === 'dark' ? 'light' : 'dark';
  document.documentElement.dataset.theme = next;
  try { localStorage.setItem('specforge.theme', next); } catch (e) { /* ignore */ }
}

/* ------------------------------------------------------------------ init */


/* ------------------------------------------------------------------ source choice (UI-SRC-1..3) */

const SOURCE_KEY = 'specforge.source';

/** Both flows keep their state, so switching never discards work (UI-SRC-3). */
function setSource(src) {
  if (src !== 'spec' && src !== 'db') src = null;
  state.source = src;
  try { if (src) localStorage.setItem(SOURCE_KEY, src); } catch (e) { /* storage unavailable */ }
  $('#sourceSwitch').value = src || '';
  $$('.choice[data-source]').forEach(c => c.setAttribute('aria-pressed', String(c.dataset.source === src)));
  $('#specFlow').hidden = src !== 'spec';
  $('#dbFlow').hidden = src !== 'db';
  $$('.db-only').forEach(e => { e.hidden = src !== 'db'; });
  $$('[data-flow="spec"]').forEach(e => { e.hidden = src === 'db'; });
  if (src === 'db') initDatabase();
  enableSteps();
  goStep('1');
}

/* ------------------------------------------------------------------ database: source (CONN-1..4) */

function dbKind() { return (state.kinds || []).find(k => k.id === $('#dbKind').value) || {}; }

async function initDatabase() {
  if (!state.db) state.db = {};
  if (!state.kinds) {
    try { state.kinds = await api('/api/db/kinds'); } catch (e) { dbError(e); return; }
    renderEngineTiles();
    onDbKindChange();
  }
  refreshConnections();
}

const TIER_NAMES = { 1: 'Relational databases', 2: 'Document and search stores', 3: 'Files' };

/** UI-SRC-5: engines as a searchable grid of tiles grouped by tier, each with its read-only controls visible. */
function renderEngineTiles() {
  const q = $('#engineSearch').value.trim().toLowerCase();
  const box = $('#engineTiles');
  box.replaceChildren();
  const tiers = [...new Set(state.kinds.map(k => k.tier))].sort();
  for (const tier of tiers) {
    const kinds = state.kinds.filter(k => k.tier === tier && (!q || k.label.toLowerCase().includes(q) || k.id.toLowerCase().includes(q)));
    if (!kinds.length) continue;
    const grid = el('div', { class: 'tiles', role: 'radiogroup', 'aria-label': TIER_NAMES[tier] || 'Tier ' + tier });
    for (const k of kinds) {
      const on = $('#dbKind').value === k.id;
      grid.append(el('button', {
        type: 'button', class: 'tile', role: 'radio', 'aria-checked': String(on), tabindex: on ? '0' : '-1', disabled: !k.available,
        title: k.available ? k.controls : 'The ' + k.label + ' driver is not in this build',
        onclick: () => { $('#dbKind').value = k.id; renderEngineTiles(); onDbKindChange(); focusTile(k.id); },
        onkeydown: ev => tileKeys(ev, grid)
      },
        el('strong', null, k.label),
        el('span', { class: 'badge ' + (k.readOnlyEnforced ? 'ok' : 'warn') }, k.readOnlyEnforced ? 'read-only enforced' : 'use a read-only login'),
        el('span', { class: 'muted small-text' }, k.fileBased ? 'file' : 'port ' + k.defaultPort)));
    }
    box.append(el('p', { class: 'tier-name' }, TIER_NAMES[tier] || 'Tier ' + tier), grid);
  }
  if (!box.children.length) box.append(el('p', { class: 'muted' }, 'No engine matches "' + q + '".'));
}

function focusTile(id) {
  const label = (dbKind() || {}).label;
  const t = [...$$('#engineTiles .tile')].find(b => b.querySelector('strong').textContent === label);
  if (t && document.activeElement && document.activeElement.classList.contains('tile')) t.focus();
}

/** Arrow keys move between tiles, as in a radio group. */
function tileKeys(ev, grid) {
  const tiles = [...grid.querySelectorAll('.tile:not(:disabled)')];
  const i = tiles.indexOf(ev.target);
  const next = { ArrowRight: i + 1, ArrowDown: i + 1, ArrowLeft: i - 1, ArrowUp: i - 1 }[ev.key];
  if (next === undefined || !tiles.length) return;
  ev.preventDefault();
  tiles[(next + tiles.length) % tiles.length].click();
}

function onDbKindChange() {
  const k = dbKind();
  $('#dbEngineTitle').textContent = k.label ? k.label + ' connection' : 'Connection';
  $('#dbControls').replaceChildren(
    el('span', { class: 'badge ' + (k.readOnlyEnforced ? 'ok' : 'warn') }, k.readOnlyEnforced ? 'Read-only' : 'Read-only not enforceable'),
    ' ' + [k.controls, k.limits].filter(Boolean).join(' '));
  const show = { server: !k.fileBased, file: !!k.fileBased, auth: k.id !== 'SQLITE', schemas: k.id !== 'SQLITE', ssl: !!k.ssl };
  $$('#dbFlow [data-show]').forEach(f => { f.hidden = !show[f.dataset.show]; });
  $('#dbDatabaseLabel').textContent = k.databaseLabel || 'Database';
  $('#dbPath').placeholder = 'e.g. ' + (k.example || '');
  $('#dbSchemas').placeholder = { POSTGRESQL: 'public', SQLSERVER: 'dbo', H2: 'PUBLIC', MYSQL: 'the database above',
    MARIADB: 'the database above', ORACLE: 'your user', DB2: 'every schema with tables' }[k.id] || '';
  const port = $('#dbPort');
  if (!port.value || Number(port.value) === state.db.lastDefaultPort) port.value = k.defaultPort || '';
  state.db.lastDefaultPort = k.defaultPort;
  if (k.id === 'H2' && !$('#dbUser').value) $('#dbUser').value = 'sa';
  dbDirty();
}

/** CONN-5: the SQL for a structure-only login, filled in from the form. SpecForge never runs it. */
function showLeastPrivilege() {
  const k = dbKind();
  const schema = $('#dbSchemas').value.split(',')[0].trim() || { POSTGRESQL: 'public', SQLSERVER: 'dbo' }[k.id] || 'your_schema';
  const sql = (k.leastPrivilegeSql || '').replaceAll('{database}', $('#dbDatabase').value.trim() || 'your_database')
    .replaceAll('{schema}', schema).replaceAll('{user}', 'specforge_reader');
  $('#lpTitle').textContent = 'Least-privilege login for ' + k.label;
  const code = $('#lpCode');
  code.textContent = sql;
  if (window.hljs) { code.removeAttribute('data-highlighted'); code.className = 'hljs language-sql'; hljs.highlightElement(code); }
  $('#lpDialog').showModal();
}

/** DRV-3, DRV-5: every bundled driver with its version, license and self-check result. */
async function showDrivers() {
  const body = $('#driversBody');
  body.replaceChildren(el('tr', null, el('td', { colspan: '5', class: 'muted' }, 'Loading...')));
  $('#driversDialog').showModal();
  try {
    const drivers = await api('/api/db/drivers');
    body.replaceChildren(...drivers.map(d => el('tr', null,
      el('td', null, d.label), el('td', null, el('code', null, d.artifact)), el('td', null, d.version || ''),
      el('td', { class: 'small-text' }, d.license),
      el('td', null, el('span', { class: 'badge ' + (d.available ? 'ok' : 'warn') }, d.available ? 'loaded' : d.status)))));
  } catch (e) { body.replaceChildren(el('tr', null, el('td', { colspan: '5' }, e.message))); }
}

function dbDirty() { state.db.connectionId = null; }

function dbForm() {
  const v = id => $(id).value.trim();
  const c = {
    name: v('#dbName'), kind: $('#dbKind').value, host: v('#dbHost'), port: Number($('#dbPort').value) || 0, path: v('#dbPath'),
    database: v('#dbDatabase'), user: v('#dbUser'), passwordEnv: v('#dbPasswordEnv'), sslMode: $('#dbSsl').value,
    schemas: v('#dbSchemas').split(',').map(x => x.trim()).filter(Boolean)
  };
  if ($('#dbPassword').value) c.password = $('#dbPassword').value;
  if (v('#dbUrl')) c.url = v('#dbUrl');
  return c;
}

function dbSource() {
  const schemas = $('#dbSchemas').value.split(',').map(x => x.trim()).filter(Boolean);
  return state.db.connectionId ? { connectionId: state.db.connectionId, schemas } : { connection: dbForm(), schemas };
}

function dbError(e) {
  const box = $('#dbError');
  if (!e) { box.hidden = true; return; }
  const d = e.data || {};
  box.textContent = (d.message || e.message) + (d.hint ? ' ' + d.hint : '');
  box.hidden = false;
}

async function refreshConnections() {
  let list = [];
  try { list = (await api('/api/db/connections')).connections || []; } catch (e) { /* listed when available */ }
  const box = $('#savedList');
  box.replaceChildren();
  for (const c of list) {
    const label = (c.name || c.database || c.id) + ' · ' + ((state.kinds || []).find(k => k.id === c.kind) || {}).label;
    box.append(el('span', { class: 'saved' },
      el('button', { type: 'button', class: 'btn small', onclick: () => useConnection(c) }, 'Reconnect to ' + label),
      el('button', { type: 'button', class: 'btn small ghost icon', 'aria-label': 'Forget ' + label, title: 'Forget this connection',
        onclick: async () => { if (confirm('Forget the saved connection "' + label + '"?')) { await api('/api/db/connections/' + c.id, { method: 'DELETE' }); refreshConnections(); } } }, '×')));
  }
  $('#savedConnections').hidden = list.length === 0;
}

function useConnection(c) {
  $('#dbKind').value = c.kind; renderEngineTiles(); onDbKindChange();
  $('#dbPath').value = c.path || '';
  $('#dbName').value = c.name || ''; $('#dbHost').value = c.host || ''; $('#dbPort').value = c.port || '';
  $('#dbDatabase').value = c.database || ''; $('#dbUser').value = c.user || ''; $('#dbPassword').value = '';
  $('#dbPassword').placeholder = c.secrets && c.secrets.password && c.secrets.password.set ? 'saved (' + c.secrets.password.masked + ')' : 'not saved';
  $('#dbPasswordEnv').value = c.passwordEnv || ''; $('#dbSsl').value = c.sslMode || ''; $('#dbUrl').value = '';
  $('#dbSchemas').value = (c.schemas || []).join(', ');
  state.db.connectionId = c.id;
  $('#dbStatus').textContent = 'Using the saved connection "' + (c.name || c.id) + '".';
}

async function dbTest() {
  const btn = $('#btnDbTest');
  busy(btn, true); dbError(null);
  try {
    const info = await api('/api/db/test', { method: 'POST', body: dbSource() });
    $('#dbStatus').textContent = '✓ ' + info.serverVersion + ' · schemas: ' + (info.schemas.join(', ') || 'none') +
      (info.warnings.length ? ' · ' + info.warnings.join(' ') : '');
    if (!$('#dbSchemas').value && info.schemas.length === 1) $('#dbSchemas').value = info.schemas[0];
  } catch (e) { dbError(e); $('#dbStatus').textContent = ''; } finally { busy(btn, false); }
}

async function dbSave() {
  const btn = $('#btnDbSave');
  busy(btn, true); dbError(null);
  try {
    const body = dbForm();
    if (!body.name) { $('#dbName').focus(); throw new Error('Give the connection a name to save it.'); }
    const saved = state.db.connectionId
      ? await api('/api/db/connections/' + state.db.connectionId, { method: 'PUT', body })
      : await api('/api/db/connections', { method: 'POST', body });
    toast('Connection saved; its password is stored encrypted.');
    await refreshConnections();
    useConnection(saved);
  } catch (e) { dbError(e); } finally { busy(btn, false); }
}

async function dbRead() {
  const btn = $('#btnDbRead');
  busy(btn, true); dbError(null);
  try {
    const started = await api('/api/db/introspect', { method: 'POST', body: dbSource() });
    let snapshotId = started.snapshotId;
    if (!snapshotId) snapshotId = await waitForJob(started.jobId);
    if (!snapshotId) return;
    await loadSnapshot(snapshotId);
    goStep('select');
  } catch (e) { dbError(e); } finally { busy(btn, false); $('#dbProgress').hidden = true; }
}

/** Polls a job (D8, UIX-7) until it ends; returns its snapshot id, or null when cancelled. */
async function waitForJob(jobId) {
  state.db.jobId = jobId;
  $('#dbProgress').hidden = false;
  while (true) {
    const j = await api('/api/jobs/' + jobId);
    $('#dbProgressBar').style.width = Math.round((j.progress || 0) * 100) + '%';
    $('#dbProgressText').textContent = j.phase || 'Working…';
    if (j.state === 'DONE') return j.result.snapshotId;
    if (j.state === 'CANCELLED') { $('#dbStatus').textContent = 'Cancelled.'; return null; }
    if (j.state === 'FAILED') { const e = new Error(j.error.message); e.data = j.error; throw e; }
    await new Promise(r => setTimeout(r, 300));
  }
}

async function loadSnapshot(snapshotId) {
  state.db.snapshotId = snapshotId;
  state.db.snapshot = await api('/api/db/snapshot/' + snapshotId + '?limit=500');
  const plan = state.config.databasePlan;
  if (!plan || !plan.source || plan.source.engine !== state.db.snapshot.engine) {
    state.config.databasePlan = { planVersion: 1, select: { include: [], exclude: [] }, entities: {} };
  }
  await refreshPlan();
  $('#dbStatus').textContent = state.db.snapshot.title + ': ' + state.db.snapshot.total + ' tables read (' + state.db.snapshot.serverVersion + ').';
}

let planTimer;
function planChanged() {
  saveLocal();
  clearTimeout(planTimer);
  planTimer = setTimeout(async () => { await refreshPlan(); if (state.step === 'map') renderMap(); if (state.step === 'api') renderApi(); }, 250);
}

async function refreshPlan() {
  const r = await api('/api/db/plan/default', { method: 'POST', body: { snapshotId: state.db.snapshotId, plan: state.config.databasePlan } });
  state.db.planView = r;
  const keep = state.config.databasePlan;
  state.config.databasePlan = Object.assign({}, r.plan, { select: keep.select, entities: keep.entities, controllers: keep.controllers || [] });
  saveLocal();
  enableSteps();
}

/* ------------------------------------------------------------------ database: select (SEL-1, SEL-4) */

function patternMatch(pattern, name) {
  const rx = new RegExp('^' + pattern.split('*').map(x => x.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('.*') + '$');
  return rx.test(name);
}
function isSelected(q) {
  const sel = state.config.databasePlan.select;
  let on = sel.include.length === 0 || sel.include.some(p => patternMatch(p, q));
  if (sel.exclude.some(p => patternMatch(p, q))) on = false;
  return on;
}
function setSelected(q, on) {
  const sel = state.config.databasePlan.select;
  sel.exclude = sel.exclude.filter(x => x !== q);
  if (sel.include.length) {
    sel.include = sel.include.filter(x => x !== q && x !== '(none)');
    if (on) sel.include.push(q);
    if (!sel.include.length) sel.include = ['(none)'];
  } else if (!on) sel.exclude.push(q);
  planChanged();
}

function renderSelect() {
  const snap = state.db.snapshot, view = state.db.planView;
  if (!snap || !view) return;
  const q = ($('#selSearch').value || '').toLowerCase();
  const joins = new Set(view.joinTables);
  const body = $('#selBody');
  body.replaceChildren();
  let shown = 0;
  for (const t of snap.tables) {
    if (q && !t.qualifiedName.toLowerCase().includes(q)) continue;
    shown++;
    const cb = el('input', { type: 'checkbox', 'aria-label': 'Generate ' + t.qualifiedName });
    cb.checked = isSelected(t.qualifiedName);
    cb.addEventListener('change', () => { setSelected(t.qualifiedName, cb.checked); });
    const note = joins.has(t.qualifiedName) ? el('span', { class: 'kind' }, ' many-to-many link') :
      view.excluded[t.qualifiedName] && view.excluded[t.qualifiedName] !== 'not selected' ? el('span', { class: 'lock' }, ' ' + view.excluded[t.qualifiedName]) : null;
    body.append(el('tr', null,
      el('td', null, cb),
      el('td', null, el('code', null, t.qualifiedName), note, t.comment ? el('div', { class: 'muted small-text' }, t.comment) : null),
      el('td', { class: 'num' }, String(t.columns)),
      el('td', null, t.primaryKey.length ? t.primaryKey.join(', ') : el('span', { class: 'lock' }, 'none')),
      el('td', { class: 'small-text' }, t.references.join(', ')),
      el('td', { class: 'num' }, t.rowEstimate == null ? '–' : Number(t.rowEstimate).toLocaleString())));
  }
  const total = snap.total;
  $('#selInfo').textContent = view.entities.length + ' entities will be generated' + (view.joinTables.length ? ', ' + view.joinTables.length + ' link table(s) as many-to-many' : '') +
    '. ' + (total > snap.tables.length ? 'Showing the first ' + snap.tables.length + ' of ' + total + ' tables.' : shown + ' of ' + total + ' tables shown.');
  const warn = view.warnings || [];
  $('#selWarnings').hidden = warn.length === 0;
  msgList($('#selWarnList'), warn.map(w => ({ level: 'WARN', text: w })));
}

/* ------------------------------------------------------------------ database: map (read-only in R1, class names editable) */

function entityDecision(table) {
  const ents = state.config.databasePlan.entities;
  if (!ents[table]) ents[table] = { operations: {}, keyColumns: [], unselectedForeignKeys: {} };
  const d = ents[table];
  if (!d.columns) d.columns = {};
  if (!d.relations) d.relations = {};
  return d;
}

/** A decision equal to the suggestion (or emptied) is removed, so the plan keeps decisions only (PLAN-5). */
function decide(obj, key, value, suggested) {
  if (value === '' || value == null || value === suggested) delete obj[key];
  else obj[key] = value;
  planChanged();
}

function columnDecision(table, column) {
  const d = entityDecision(table);
  if (!d.columns[column]) d.columns[column] = {};
  return d.columns[column];
}

function relationDecision(table, column) {
  const d = entityDecision(table);
  if (!d.relations[column]) d.relations[column] = {};
  return d.relations[column];
}

function existingDecision(table) { return (state.config.databasePlan.entities || {})[table] || {}; }

function yours(isYours, reset) {
  if (!isYours) return el('span', { class: 'sugg-chip', title: 'SpecForge suggestion' }, 'suggested');
  return el('span', { class: 'row-inline' }, el('span', { class: 'yours-chip' }, 'yours'),
    el('button', { type: 'button', class: 'btn icon small ghost', title: 'Back to the suggestion', 'aria-label': 'Back to the suggestion', onclick: reset }, '↺'));
}

function textInput(value, label, onchange, extra) {
  const i = el('input', Object.assign({ type: 'text', value: value || '', 'aria-label': label, spellcheck: 'false' }, extra || {}));
  i.addEventListener('change', () => onchange(i.value.trim()));
  return i;
}

/* ------------------------------------------------------------------ database: mapping editor (MAP-11) */

const KEY_STRATEGIES = ['IDENTITY', 'SEQUENCE', 'UUID', 'ASSIGNED'];

function renderMap() {
  const view = state.db.planView;
  if (!view) return;
  const q = ($('#mapSearch').value || '').trim().toLowerCase();
  if (!state.db.openCards) state.db.openCards = new Set();
  const list = $('#mapList');
  list.replaceChildren();
  for (const e of view.entities) {
    if (q && !e.className.toLowerCase().includes(q) && !e.table.toLowerCase().includes(q)) continue;
    list.append(mapCard(e, view));
  }
  if (!list.children.length) list.append(el('p', { class: 'muted' }, q ? 'No class or table matches "' + q + '".' : 'No table is selected.'));
  renderJoinTables(view);
}

function mapCard(e, view) {
  const d = existingDecision(e.table);
  const sug = e.suggested || {};
  const edits = countDecisions(d);
  const card = el('details', { class: 'card compact map-card', 'data-table': e.table });
  card.open = state.db.openCards.has(e.table);
  card.addEventListener('toggle', () => { card.open ? state.db.openCards.add(e.table) : state.db.openCards.delete(e.table); });

  const className = textInput(e.className, 'Class name for ' + e.table, v => decide(entityDecision(e.table), 'className', v, sug.className), { class: 'class-name' });
  className.addEventListener('click', ev => ev.stopPropagation());
  const keyText = e.id.strategy === 'SEQUENCE' ? 'sequence ' + e.id.sequence : e.id.strategy.toLowerCase();
  card.append(el('summary', null, className,
    el('span', { class: 'muted small-text' }, ' ', el('code', null, e.table), ' · ', e.path, ' · key ', e.id.columns.join(', '), ' (', keyText, ')'),
    e.readOnly ? el('span', { class: 'lock' }, ' read-only') : null,
    edits ? el('span', { class: 'yours-chip' }, edits + (edits === 1 ? ' change' : ' changes')) : null,
    e.warnings.length ? el('span', { class: 'badge warn' }, e.warnings.length + ' to check') : null));

  // class, API and controller (OPS-5)
  const path = textInput(e.path, 'API path for ' + e.className, v => decide(entityDecision(e.table), 'path', v.replace(/^\/+/, ''), (sug.path || '').replace(/^\/+/, '')));
  const ctrlOn = el('input', { type: 'checkbox', 'aria-label': 'Generate the controller of ' + e.className });
  ctrlOn.checked = e.controller;
  ctrlOn.addEventListener('change', () => decide(entityDecision(e.table), 'controller', ctrlOn.checked ? null : false));
  const ctrlName = textInput(e.controllerName, 'Controller class of ' + e.className, v => decide(entityDecision(e.table), 'controllerName', v, e.className + 'Controller'));
  const ro = el('input', { type: 'checkbox', 'aria-label': e.className + ' read-only' });
  ro.checked = e.readOnly;
  ro.disabled = e.readOnly && !d.readOnly;
  ro.addEventListener('change', () => decide(entityDecision(e.table), 'readOnly', ro.checked ? true : null));
  card.append(el('div', { class: 'grid two tight editor-grid' },
    el('label', { class: 'field' }, el('span', null, 'API path ', yours(d.path != null, () => decide(entityDecision(e.table), 'path', null))), path),
    el('div', { class: 'field' }, el('span', null, 'Controller ', yours(d.controller === false || d.controllerName != null, () => {
      delete entityDecision(e.table).controller; decide(entityDecision(e.table), 'controllerName', null);
    })), el('div', { class: 'row gap' }, el('label', { class: 'check' }, ctrlOn, 'generate'), ctrlName)),
    el('label', { class: 'check' }, ro, 'Read-only (list and read only)', e.readOnly && !d.readOnly ? el('span', { class: 'muted small-text' }, ' – ' + e.readOnlyReason) : null)));

  // module of the modular architecture (ARCH-3)
  if (state.config.architecture === 'MODULAR') {
    const plan = state.config.databasePlan;
    if (!plan.modules) plan.modules = {};
    const mod = textInput(e.module, 'Module of ' + e.className, v => {
      const name = (v || '').trim().toLowerCase();
      if (!name || name === sug.module) delete plan.modules[e.table]; else plan.modules[e.table] = name;
      planChanged();
    });
    card.append(el('label', { class: 'field' },
      el('span', null, 'Module ', yours(plan.modules[e.table] != null, () => { delete plan.modules[e.table]; planChanged(); })), mod,
      el('small', { class: 'muted' }, 'Suggested: tables linked by foreign keys. A table of another module is referred to by its id.')));
  }

  // key (MAP-1, MAP-7)
  if (e.id.kind !== 'COMPOSITE') {
    const strat = el('select', { 'aria-label': 'Key strategy of ' + e.className }, KEY_STRATEGIES.map(k => el('option', { value: k }, k.toLowerCase())));
    strat.value = e.id.strategy;
    strat.addEventListener('change', () => decide(entityDecision(e.table), 'idStrategy', strat.value, sug.idStrategy));
    const seq = textInput(e.id.sequence, 'Sequence of ' + e.className, v => decide(entityDecision(e.table), 'sequenceName', v, sug.sequence), { placeholder: 'schema.sequence' });
    const alloc = el('input', { type: 'number', min: '1', value: e.id.allocationSize, 'aria-label': 'Allocation size', class: 'narrow' });
    alloc.addEventListener('change', () => decide(entityDecision(e.table), 'allocationSize', Number(alloc.value) || null));
    const seqBox = el('span', { class: 'row gap' }, seq, el('span', { class: 'muted small-text' }, 'allocation'), alloc);
    seqBox.hidden = e.id.strategy !== 'SEQUENCE';
    card.append(el('div', { class: 'field' }, el('span', null, 'Key ', el('code', null, e.id.columns[0]), ' (', e.id.javaType, ') ',
      yours(d.idStrategy != null, () => { const x = entityDecision(e.table); delete x.sequenceName; delete x.allocationSize; decide(x, 'idStrategy', null); })),
      el('div', { class: 'row gap wrap' }, strat, seqBox)));
  } else {
    card.append(el('p', { class: 'muted small-text' }, 'Composite key (', e.id.columns.join(', '), '): an embedded Key class; key columns that are foreign keys reuse the relation (@MapsId).'));
  }

  // columns (MAP-3, MAP-6)
  const rows = e.columns.map(c => columnRow(e, c, view));
  card.append(el('div', { class: 'table-wrap' }, el('table', { class: 'table editor' },
    el('thead', null, el('tr', null, el('th', null, 'Column'), el('th', null, 'Property'), el('th', null, 'Java type'),
      el('th', { class: 'center' }, 'Include'), el('th', { class: 'center' }, 'Filter'), el('th', { class: 'center' }, 'Sort'),
      el('th', { class: 'center', title: 'Searched by the list parameter q (Settings → List endpoints)' }, 'Search'),
      el('th', { class: 'center', title: 'Offered as …From / …To on the list (Settings → List endpoints)' }, 'Range'), el('th', null, 'Notes'))),
    el('tbody', null, rows))));

  // relations (MAP-4)
  if (e.relations.length) {
    card.append(el('h4', null, 'Relations'), el('ul', { class: 'plain rel-list' }, e.relations.map(r => relationRow(e, r))));
  }
  if (e.manyToMany.length || e.inverses.length) {
    card.append(el('ul', { class: 'plain small-text' },
      e.manyToMany.map(m => el('li', null, el('code', null, m.name), ' → many ', m.target, ' via ', el('code', null, m.joinTable), ' (DTO ', el('code', null, m.dtoName), ')')),
      e.inverses.map(i => el('li', null, el('code', null, i.name), ' ← all ', i.source, ' rows referencing this one (', i.source, '.', i.mappedBy, ', not in the DTO)'))));
  }
  if (e.joinTable) {
    card.append(el('p', null, el('button', { type: 'button', class: 'btn small', onclick: () => decide(entityDecision(e.table), 'asEntity', null) }, 'Map as many-to-many again')));
  }
  if (e.warnings.length) card.append(el('ul', { class: 'messages' }, e.warnings.map(w => el('li', { class: 'warn' }, w))));
  return card;
}

function countDecisions(d) {
  let n = 0;
  for (const k of ['className', 'path', 'controllerName', 'controller', 'idStrategy', 'sequenceName', 'allocationSize', 'readOnly', 'asEntity']) if (d[k] != null) n++;
  for (const c of Object.values(d.columns || {})) n += Object.values(c).filter(v => v != null).length;
  for (const r of Object.values(d.relations || {})) n += Object.values(r).filter(v => v != null).length;
  return n;
}

function columnRow(e, c, view) {
  const d = (existingDecision(e.table).columns || {})[c.column] || {};
  const has = Object.values(d).some(v => v != null);
  const reset = () => { delete entityDecision(e.table).columns[c.column]; planChanged(); };
  const sug = c.suggested || {};
  const name = textInput(c.name || '', 'Property for column ' + c.column, v => decide(columnDecision(e.table, c.column), 'name', v, sug.name));
  name.disabled = !c.included || (!!c.relation && !c.javaType);
  const type = el('select', { 'aria-label': 'Java type for column ' + c.column }, view.javaTypes.map(t => el('option', { value: t }, t)));
  type.value = c.javaType || 'String';
  type.disabled = !c.included || c.key && e.id.kind === 'COMPOSITE' || !!c.relation;
  type.addEventListener('change', () => decide(columnDecision(e.table, c.column), 'javaType', type.value, sug.javaType));
  const include = el('input', { type: 'checkbox', 'aria-label': 'Include column ' + c.column });
  include.checked = !!c.included;
  include.disabled = c.key;
  include.addEventListener('change', () => {
    const cd = columnDecision(e.table, c.column);
    if (include.checked) { delete cd.include; if (!c.included && !c.javaType && !cd.javaType) cd.javaType = 'String'; planChanged(); }
    else decide(cd, 'include', false);
  });
  const box = (prop, label) => {
    const cb = el('input', { type: 'checkbox', 'aria-label': label + ' by ' + c.column });
    cb.checked = !!c[prop];
    cb.disabled = !c.included;
    cb.addEventListener('change', () => { columnDecision(e.table, c.column)[prop] = cb.checked; planChanged(); });
    return cb;
  };
  const notes = [];
  if (c.key) notes.push(el('span', { class: 'badge' }, 'key'));
  if (c.relation) notes.push(el('span', { class: 'badge' }, '→ ' + c.relation));
  if (c.generated) notes.push(el('span', { class: 'badge' }, 'generated'));
  if (!c.included && c.reason) notes.push(el('span', { class: 'muted small-text' }, c.reason));
  if (c.validations && c.validations.length) notes.push(el('span', { class: 'muted small-text' }, c.validations.join(' ')));
  return el('tr', { class: c.included ? '' : 'excluded' },
    el('td', null, el('code', null, c.column), el('div', { class: 'muted small-text' }, c.sqlType + (c.nullable ? '' : ' not null'))),
    el('td', null, name), el('td', null, type),
    el('td', { class: 'center' }, include), el('td', { class: 'center' }, box('filter', 'Filter')), el('td', { class: 'center' }, box('sort', 'Sort')),
    el('td', { class: 'center' }, box('search', 'Search')), el('td', { class: 'center' }, box('range', 'Range')),
    el('td', null, notes, has ? yours(true, reset) : null));
}

function relationRow(e, r) {
  const d = (existingDecision(e.table).relations || {})[r.column] || {};
  const sug = r.suggested || {};
  const name = textInput(r.name, 'Relation property for ' + r.column, v => decide(relationDecision(e.table, r.column), 'name', v, sug.name), { class: 'narrow-text' });
  const dto = textInput(r.dtoName, 'DTO property for ' + r.column, v => decide(relationDecision(e.table, r.column), 'dtoName', v, sug.dtoName), { class: 'narrow-text' });
  dto.disabled = !r.dtoName;
  const inv = el('input', { type: 'checkbox', 'aria-label': 'Inverse collection on ' + r.target });
  inv.checked = !!r.inverse;
  inv.addEventListener('change', () => decide(relationDecision(e.table, r.column), 'inverse', inv.checked ? true : null));
  const has = Object.values(d).some(v => v != null);
  return el('li', { class: 'row gap wrap' }, el('code', null, r.column), '→', el('strong', null, r.target),
    el('span', { class: 'muted small-text' }, 'property'), name, el('span', { class: 'muted small-text' }, 'DTO'), dto,
    el('label', { class: 'check' }, inv, 'collection on ', r.target), r.mapsId ? el('span', { class: 'badge' }, 'part of the key') : null,
    has ? yours(true, () => { delete entityDecision(e.table).relations[r.column]; planChanged(); }) : null);
}

function renderJoinTables(view) {
  const list = $('#joinList');
  list.replaceChildren(...view.joinTables.map(t => el('li', { class: 'row gap' }, el('code', null, t), el('span', { class: 'muted small-text' }, 'many-to-many'),
    el('button', { type: 'button', class: 'btn small', onclick: () => { entityDecision(t).asEntity = true; planChanged(); } }, 'Generate as an entity'))));
  $('#joinCard').hidden = view.joinTables.length === 0;
}

/* ------------------------------------------------------------------ database: API (OPS-1, OPS-2, OPS-5) and use cases (OPS-3, OPS-4) */

const OPS = ['list', 'read', 'create', 'update', 'patch', 'delete'];
function renderApi() {
  const view = state.db.planView;
  if (!view) return;
  const body = $('#apiBody');
  body.replaceChildren();
  for (const e of view.entities) {
    const ctrl = el('input', { type: 'checkbox', 'aria-label': 'Generate the controller of ' + e.className });
    ctrl.checked = e.controller;
    ctrl.addEventListener('change', () => decide(entityDecision(e.table), 'controller', ctrl.checked ? null : false));
    const cells = OPS.map(op => {
      const cb = el('input', { type: 'checkbox', 'aria-label': op + ' ' + e.className });
      cb.checked = !!e.operations[op];
      const write = !['list', 'read'].includes(op);
      cb.disabled = !e.controller || e.readOnly && write;
      const moved = !e.operations[op] && e.serviceOperations[op];
      cb.title = moved ? 'Served by a use-case controller' : '';
      cb.addEventListener('change', () => { entityDecision(e.table).operations[op] = cb.checked; planChanged(); });
      return el('td', { class: 'center' + (moved ? ' moved' : '') }, cb);
    });
    body.append(el('tr', { class: e.controller ? '' : 'excluded' },
      el('td', null, el('label', { class: 'check' }, ctrl, e.controllerName)), el('td', null, el('code', null, e.path)), cells));
  }
  renderUseCases(view);
}

const UC_KINDS = [
  ['ENTITY', 'Operation of one table'],
  ['AGGREGATE_CREATE', 'Create a parent with its children'],
  ['AGGREGATE_UPDATE', 'Update a parent and replace its children'],
  ['WITH_CHILDREN', 'Read a parent with its children'],
  ['CHILDREN', "List a parent's children"]];

function controllersPlan() {
  const p = state.config.databasePlan;
  if (!p.controllers) p.controllers = [];
  return p.controllers;
}

function renderUseCases(view) {
  const list = $('#ucList');
  list.replaceChildren();
  const byClass = Object.fromEntries(view.entities.map(e => [e.table, e]));
  controllersPlan().forEach((c, ci) => {
    const effective = view.useCases.find(u => u.name.toLowerCase() === (c.name || '').replace(/controller$/i, '').toLowerCase());
    const name = textInput(c.name, 'Use-case controller name', v => { c.name = v; planChanged(); });
    const path = textInput(c.path || '', 'Base path of ' + c.name, v => { c.path = v || null; planChanged(); }, { placeholder: effective ? effective.path : '/' });
    const rows = c.operations.map((o, oi) => {
      const kind = (UC_KINDS.find(k => k[0] === o.kind) || [o.kind, o.kind])[1];
      const ent = byClass[o.entity], child = byClass[o.child];
      const what = o.kind === 'ENTITY' ? [el('strong', null, o.operation), ' ', ent ? ent.className : o.entity]
        : [ent ? ent.className : o.entity, ' + ', child ? child.className : o.child];
      const keep = o.kind === 'ENTITY' ? el('label', { class: 'check small-text' }, (() => {
        const cb = el('input', { type: 'checkbox', 'aria-label': 'Also keep it in the table controller' });
        cb.checked = !!o.keepInEntityController;
        cb.addEventListener('change', () => { o.keepInEntityController = cb.checked || null; planChanged(); });
        return cb;
      })(), 'also in ', ent ? ent.controllerName : 'its controller') : null;
      const eff = effective && effective.operations.find(x => x.kind === o.kind && x.entity === (ent || {}).className && (o.kind !== 'ENTITY' || x.method.toLowerCase().startsWith(o.operation)));
      return el('li', { class: 'row gap wrap uc-op' }, el('span', { class: 'badge' }, kind), what,
        eff ? el('code', { class: 'small-text' }, eff.http + ' ' + eff.path) : el('span', { class: 'badge warn' }, 'not generated, see below'), keep,
        el('button', { type: 'button', class: 'btn small ghost', 'aria-label': 'Remove this operation', onclick: () => { c.operations.splice(oi, 1); planChanged(); } }, 'Remove'));
    });
    list.append(el('div', { class: 'uc' },
      el('div', { class: 'row gap wrap' }, el('label', { class: 'field inline' }, el('span', null, 'Name'), name),
        el('label', { class: 'field inline' }, el('span', null, 'Base path'), path),
        el('button', { type: 'button', class: 'btn small ghost', onclick: () => { if (confirm('Remove the use-case controller "' + c.name + '"?')) { controllersPlan().splice(ci, 1); planChanged(); } } }, 'Remove controller')),
      rows.length ? el('ul', { class: 'plain' }, rows) : el('p', { class: 'muted small-text' }, 'No operation yet.'),
      addOperationForm(c, view)));
  });
  const warnings = view.warnings.filter(w => w.startsWith('Use case') || w.startsWith('Use-case') || w.startsWith('Two use-case') || w.startsWith('A use-case'));
  $('#ucWarnings').replaceChildren(...warnings.map(w => el('li', { class: 'warn' }, w)));
}

function addOperationForm(c, view) {
  const kind = el('select', { 'aria-label': 'Kind of operation' }, UC_KINDS.map(k => el('option', { value: k[0] }, k[1])));
  const entity = el('select', { 'aria-label': 'Table' }, view.entities.map(e => el('option', { value: e.table }, e.className)));
  const op = el('select', { 'aria-label': 'Operation' }, OPS.map(o => el('option', { value: o }, o)));
  const child = el('select', { 'aria-label': 'Child table' });
  const fillChildren = () => {
    const parent = view.entities.find(e => e.table === entity.value);
    const kids = view.entities.filter(e => parent && e.relations.some(r => r.target === parent.className));
    child.replaceChildren(...kids.map(e => el('option', { value: e.table }, e.className)));
    if (!kids.length) child.append(el('option', { value: '' }, 'no table references it'));
  };
  const sync = () => {
    const isEntity = kind.value === 'ENTITY';
    op.hidden = !isEntity;
    child.hidden = isEntity;
    fillChildren();
  };
  kind.addEventListener('change', sync);
  entity.addEventListener('change', fillChildren);
  sync();
  const form = el('form', { class: 'row gap wrap uc-add' }, el('span', { class: 'muted small-text' }, 'Add'), kind, entity, op, child,
    el('button', { type: 'submit', class: 'btn small' }, 'Add operation'));
  form.addEventListener('submit', ev => {
    ev.preventDefault();
    const o = { kind: kind.value, entity: entity.value };
    if (kind.value === 'ENTITY') o.operation = op.value;
    else { if (!child.value) return toast('Choose a table that references ' + entity.selectedOptions[0].textContent); o.child = child.value; }
    c.operations.push(o);
    planChanged();
  });
  return form;
}

/* ------------------------------------------------------------------ Spring Boot Studio (STU-1 … STU-7, STU-13) */

const studio = { sets: [], set: '', templates: [], current: null, cm: null, dirty: false, item: '', compare: false, timer: null, back: '1' };
const ST_STATUS = { DEFAULT: 'Default', CUSTOM: 'Custom', CUSTOM_DEFAULT_CHANGED: 'Custom, default changed since', CUSTOM_ONLY: 'Custom (no default)' };

async function openStudio(template) {
  studio.back = state.step || '1';
  for (const id of Object.values(STEP_SECTIONS)) $('#' + id).hidden = true;
  $('#studio').hidden = false;
  window.scrollTo({ top: 0 });
  ensureEditor();
  await loadSets();
  if (template) await openTemplate(template);
}

function closeStudio() {
  if (studio.dirty && !confirm('Leave the Studio without saving your changes?')) return;
  studio.dirty = false;
  $('#studio').hidden = true;
  goStep(studio.back);
}

function ensureEditor() {
  if (studio.cm || !window.CodeMirror) return;
  // FreeMarker on top of the produced language (STU-2): directives, interpolations, comments
  CodeMirror.defineMode('freemarker-over', (config, parserConfig) => CodeMirror.overlayMode(CodeMirror.getMode(config, parserConfig.backdrop || 'text/plain'), {
    token(stream) {
      if (stream.match('<#--')) { while (!stream.eol()) { if (stream.match('-->')) break; stream.next(); } return 'comment ftl'; }
      if (stream.match(/^<\/?[#@][\w.]*/)) { while (!stream.eol() && stream.peek() !== '>') stream.next(); stream.eat('>'); return 'keyword ftl'; }
      if (stream.match('[=')) { let depth = 1; while (!stream.eol() && depth) { const ch = stream.next(); if (ch === '[') depth++; else if (ch === ']') depth--; } return 'variable-2 ftl'; }
      while (stream.next() != null && !stream.match(/^(<\/?[#@]|\[=|<#--)/, false)) { /* skip plain text */ }
      return null;
    }
  }));
  studio.cm = CodeMirror($('#stEditor'), {
    value: '', lineNumbers: true, matchBrackets: true, indentUnit: 4, tabSize: 4, lineWrapping: false,
    mode: { name: 'freemarker-over', backdrop: 'text/x-java' }, readOnly: true,
    extraKeys: { 'Ctrl-S': () => studioSave(), 'Cmd-S': () => studioSave(), 'Alt-G': 'jumpToLine' }
  });
  studio.cm.on('change', (cm, change) => {
    if (change.origin === 'setValue') return;
    studio.dirty = true;
    $('#stDirty').hidden = false;
    clearTimeout(studio.timer);
    studio.timer = setTimeout(studioPreview, 400);
  });
}

function backdropFor(name) {
  if (name.endsWith('.java.ftl')) return 'text/x-java';
  if (name.endsWith('.xml.ftl')) return 'application/xml';
  if (name.endsWith('.properties.ftl')) return 'text/x-properties';
  if (name.endsWith('.json')) return { name: 'javascript', json: true };
  return 'text/plain';
}

async function loadSets() {
  const r = await api('/api/studio/sets');
  studio.sets = r.sets;
  const active = (state.config.templatesDir || '').replaceAll('\\', '/');
  const activeSet = studio.sets.find(x => x.dir.replaceAll('\\', '/') === active);
  if (!studio.set || !studio.sets.some(x => x.name === studio.set)) studio.set = activeSet ? activeSet.name : (studio.sets[0] || {}).name || '';
  const sel = $('#stSet');
  sel.replaceChildren(...(studio.sets.length ? studio.sets.map(x => el('option', { value: x.name },
    x.name + ' (' + x.customized + ' customized)' + (activeSet && activeSet.name === x.name ? ' • in use' : ''))) : [el('option', { value: '' }, 'No set yet')]));
  sel.value = studio.set;
  $('#stInfo').textContent = studio.set
    ? (activeSet && activeSet.name === studio.set ? 'Generation uses this set: customized templates replace the defaults, all others stay default.'
      : 'Generation does not use this set yet: "Use for generation" selects it in your settings and profile.')
    : 'Customize a template to start your first set; it is created for you.';
  for (const id of ['#stUseSet', '#stExport', '#stDeleteSet']) $(id).disabled = !studio.set;
  await loadStudioTemplates();
}

async function loadStudioTemplates() {
  if (!studio.set) {
    // no set yet: list the defaults so the user can still read and preview them
    const r = await api('/api/templates?dir=');
    studio.templates = r.templates.map(t => ({ template: t.template, id: t.id, layer: t.layer, scope: t.scope, description: t.description,
      flow: t.flow || '', status: 'DEFAULT' }));
  } else {
    studio.templates = (await api('/api/studio/sets/' + encodeURIComponent(studio.set) + '/templates')).templates;
  }
  renderStudioList();
}

function currentFlow() { return state.source === 'db' ? 'DATABASE' : 'SPEC'; }

function renderStudioList() {
  const q = $('#stSearch').value.trim().toLowerCase();
  const only = $('#stOnlyFlow').checked;
  const box = $('#stTemplates');
  box.replaceChildren();
  const groups = {};
  for (const t of studio.templates) {
    if (q && !t.template.toLowerCase().includes(q) && !(t.description || '').toLowerCase().includes(q)) continue;
    if (only && t.flow && t.flow !== 'ANY' && t.flow !== currentFlow()) continue;
    (groups[t.layer] = groups[t.layer] || []).push(t);
  }
  for (const [layer, list] of Object.entries(groups)) {
    box.append(el('p', { class: 'tier-name' }, layer));
    for (const t of list) {
      const on = studio.current && studio.current.template === t.template;
      box.append(el('button', { type: 'button', class: 'st-item' + (on ? ' on' : ''), title: t.description || '', onclick: () => openTemplate(t.template) },
        el('span', null, t.template), el('span', { class: 'badge ' + (t.status === 'DEFAULT' ? '' : t.status === 'CUSTOM_DEFAULT_CHANGED' ? 'warn' : 'ok') }, ST_STATUS[t.status] || t.status)));
    }
  }
  if (!box.children.length) box.append(el('p', { class: 'muted small-text' }, 'No template matches.'));
}

async function openTemplate(name) {
  if (studio.dirty && !confirm('Discard your unsaved changes to ' + studio.current.template + '?')) return;
  let t;
  if (studio.set) t = await api('/api/studio/sets/' + encodeURIComponent(studio.set) + '/template?name=' + encodeURIComponent(name));
  else { const src = await api('/api/templates/source?dir=&name=' + encodeURIComponent(name)); t = { template: name, content: src.content, default: src.content, status: 'DEFAULT' }; }
  studio.current = t;
  studio.item = '';
  studio.compare = false;
  showTemplate();
  renderStudioList();
  studioPreview();
}

function showTemplate() {
  const t = studio.current;
  const custom = t.status !== 'DEFAULT';
  studio.cm.setOption('mode', { name: 'freemarker-over', backdrop: backdropFor(t.template) });
  studio.cm.setValue(studio.compare ? t.default || '' : t.content || '');
  studio.cm.setOption('readOnly', !custom || studio.compare);
  studio.cm.clearHistory();
  studio.dirty = false;
  $('#stDirty').hidden = true;
  $('#stName').textContent = t.template + (studio.compare ? ' (default, read-only)' : '');
  $('#stStatus').hidden = false;
  $('#stStatus').className = 'badge ' + (t.status === 'DEFAULT' ? '' : t.status === 'CUSTOM_DEFAULT_CHANGED' ? 'warn' : 'ok');
  $('#stStatus').textContent = ST_STATUS[t.status] || t.status;
  $('#stCustomize').hidden = custom;
  $('#stSave').hidden = !custom || studio.compare;
  $('#stReset').hidden = !custom || !t.default;
  $('#stCompare').hidden = !custom || !t.default;
  $('#stCompare').textContent = studio.compare ? 'Back to your version' : 'Compare with default';
  $('#stError').hidden = true;
  setTimeout(() => studio.cm.refresh(), 0);
}

async function studioCustomize() {
  const t = studio.current;
  if (!t) return;
  try {
    if (!studio.set) {
      const name = await askName('Create a template set', 'Name of your template set', 'my-templates');
      if (!name) return;
      await api('/api/studio/sets', { method: 'POST', body: { name } });
      studio.set = name;
      await loadSets();
    }
    studio.current = await api('/api/studio/sets/' + encodeURIComponent(studio.set) + '/customize', { method: 'POST', body: { template: t.template } });
    showTemplate();
    await loadSets();
    toast('Customized: edit it and save. Reset to default undoes it.');
  } catch (e) { toast(e.message); }
}

async function studioSave() {
  const t = studio.current;
  if (!t || !studio.set || t.status === 'DEFAULT' || studio.compare) return;
  const content = studio.cm.getValue();
  try {
    studio.current = await api('/api/studio/sets/' + encodeURIComponent(studio.set) + '/template', { method: 'PUT', body: { template: t.template, content } });
    // edits typed while the save was in flight stay unsaved
    studio.dirty = studio.cm.getValue() !== content;
    $('#stDirty').hidden = !studio.dirty;
    await loadSets();
    toast('Saved ' + t.template);
  } catch (e) { toast(e.message); }
}

async function studioReset() {
  const t = studio.current;
  if (!t || !confirm('Reset ' + t.template + ' to the default? Your version is deleted.')) return;
  try {
    studio.current = await api('/api/studio/sets/' + encodeURIComponent(studio.set) + '/reset', { method: 'POST', body: { template: t.template } });
    showTemplate();
    await loadSets();
    studioPreview();
  } catch (e) { toast(e.message); }
}

function studioReady() {
  if (state.source === 'db') return !!(state.db && state.db.snapshotId);
  return !!state.specId;
}

/** STU-3: renders the edited template for one sample, debounced, with the default beside it. */
async function studioPreview() {
  const t = studio.current;
  if (!t) return;
  if (t.template === 'manifest.json') { previewNote('The manifest has no preview: it decides which template renders what.'); return; }
  if (!studioReady()) { previewNote('Load a spec or read a database first: the preview renders the template with your own data.'); return; }
  const body = { set: studio.set || null, template: t.template, item: studio.item, request: genBody() };
  if (studio.dirty) body.draft = studio.cm.getValue();
  let r;
  try { r = await api('/api/studio/preview', { method: 'POST', body }); } catch (e) { previewNote(e.message); return; }
  studio.lastPreview = r;
  if (r.unavailable) { previewNote(r.unavailable); return; }
  const items = $('#stItem');
  items.replaceChildren(...(r.items || []).map(i => el('option', { value: i.key }, i.label)));
  items.value = r.item || '';
  items.disabled = !r.items || r.items.length < 2;
  $('#stNote').textContent = r.note || '';
  $('#stFile').textContent = r.fileName || '';
  $('#stTime').textContent = r.millis != null ? r.millis + ' ms' : '';
  const err = $('#stError');
  if (r.error) {
    err.hidden = false;
    err.textContent = r.error.message;
    if (r.error.line) markErrorLine(r.error.line - 1);
  } else { err.hidden = true; clearErrorMarks(); }
  renderStudioOutput();
  $('#stJava').replaceChildren(...(r.javaErrors || []).map(j => el('li', { class: 'error' }, 'Generated Java, line ' + j.line + ':' + j.column + ': ' + j.message)),
    ...(r.javaCheck ? [el('li', { class: 'info' }, 'Java syntax check ' + r.javaCheck)] : []));
  renderVars(r.model || []);
}

function previewNote(text) {
  $('#stNote').textContent = text;
  $('#stOutput').textContent = '';
  $('#stFile').textContent = '';
  $('#stItem').replaceChildren();
  $('#stVars').replaceChildren();
}

function renderStudioOutput() {
  const r = studio.lastPreview || {};
  const out = $('#stOutput');
  if (r.error) { out.textContent = ''; return; }
  if ($('#stDiff').checked && r.defaultOutput != null) {
    out.className = 'diff';
    out.replaceChildren(...lineDiff(r.defaultOutput || '', r.output || '').map(([kind, line]) =>
      el('span', { class: 'dl ' + kind }, (kind === 'add' ? '+ ' : kind === 'del' ? '- ' : '  ') + line + '\n')));
    if (r.defaultOutput === r.output) out.prepend(el('span', { class: 'dl same' }, '  (identical to the default output)\n'));
  } else {
    out.className = 'hljs';
    highlightInto(out, r.output || '', (r.fileName || '').endsWith('.java') ? 'java' : (r.fileName || '').endsWith('.xml') ? 'xml' : 'properties');
  }
}

/** Line diff (LCS) for "differences from the default" (STU-3); output is at most a few thousand lines. */
function lineDiff(a, b) {
  const x = a.split('\n'), y = b.split('\n');
  if (x.length * y.length > 4e6) return [['same', '(too large to compare line by line)']];
  const n = x.length, m = y.length;
  const dp = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) dp[i][j] = x[i] === y[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
  const out = [];
  let i = 0, j = 0;
  while (i < n && j < m) {
    if (x[i] === y[j]) { out.push(['same', x[i]]); i++; j++; }
    else if (dp[i + 1][j] >= dp[i][j + 1]) out.push(['del', x[i++]]);
    else out.push(['add', y[j++]]);
  }
  while (i < n) out.push(['del', x[i++]]);
  while (j < m) out.push(['add', y[j++]]);
  return out;
}

function markErrorLine(line) {
  clearErrorMarks();
  if (line < 0 || line >= studio.cm.lineCount()) return;
  studio.errLine = studio.cm.addLineClass(line, 'background', 'cm-error-line');
  studio.cm.scrollIntoView({ line, ch: 0 }, 80);
}

function clearErrorMarks() {
  if (studio.errLine) { studio.cm.removeLineClass(studio.errLine, 'background', 'cm-error-line'); studio.errLine = null; }
}

/** STU-4: the sample's model; clicking a variable inserts [=path] at the cursor. */
function renderVars(model) {
  const q = $('#stVarSearch').value.trim().toLowerCase();
  const build = nodes => nodes.map(n => {
    const kids = n.children && n.children.length ? build(n.children) : [];
    if (q && !n.path.toLowerCase().includes(q) && !kids.some(Boolean)) return null;
    const label = el('button', { type: 'button', class: 'vname', title: 'Insert [=' + n.path + ']', onclick: () => insertVar(n.path) }, n.name);
    const meta = el('span', { class: 'muted small-text' }, ' ', n.type || '', n.value != null ? ' = ' + n.value : '');
    if (!kids.length) return el('li', null, label, meta);
    return el('li', null, el('details', { open: !!q }, el('summary', null, label, meta), el('ul', null, kids)));
  }).filter(Boolean);
  $('#stVars').replaceChildren(...build(model));
}

function insertVar(path) {
  const t = studio.current;
  if (!t || t.status === 'DEFAULT' || studio.compare) return toast('Customize the template to edit it');
  studio.cm.replaceSelection('[=' + path + ']');
  studio.cm.focus();
}

async function studioVerify() {
  if (!studioReady()) return toast('Load a spec or read a database first');
  const btn = $('#stVerify');
  busy(btn, true);
  try {
    const body = { set: studio.set || null, template: studio.current ? studio.current.template : null, request: genBody() };
    if (studio.dirty) body.draft = studio.cm.getValue();
    let started;
    try { started = await api('/api/studio/verify', { method: 'POST', body }); } catch (e) {
      if (e.status !== 409 || !confirm(e.message + ' Compile anyway?')) throw e;
      started = await api('/api/studio/verify', { method: 'POST', body: Object.assign(body, { trustBuildFile: true }) });
    }
    const { jobId } = started;
    let job;
    do {
      await new Promise(res => setTimeout(res, 800));
      job = await api('/api/jobs/' + jobId);
      $('#stNote').textContent = job.phase || '';
    } while (job.state === 'RUNNING');
    if (job.state !== 'DONE') throw new Error((job.error && job.error.message) || 'Compilation failed to run');
    const r = job.result;
    $('#stNote').textContent = r.ok ? '✓ The whole project compiles with this template set.' : 'The project does not compile:';
    $('#stJava').replaceChildren(...r.errors.map(e => el('li', { class: 'error' }, e.file + ':' + e.line + ' ' + e.message + (e.template ? ' (template ' + e.template + ')' : ''))),
      ...(r.log ? [el('li', { class: 'error' }, el('pre', { class: 'small-text' }, r.log))] : []));
  } catch (e) { toast(e.message); } finally { busy(btn, false); }
}

async function studioNewSet() {
  const name = await askName('New template set', 'Name (letters, digits, . _ -)', 'my-templates');
  if (!name) return;
  try {
    await api('/api/studio/sets', { method: 'POST', body: { name, copyOf: studio.set && confirm('Start from a copy of "' + studio.set + '"?') ? studio.set : null } });
    studio.set = name;
    await loadSets();
  } catch (e) { toast(e.message); }
}

function wireStudio() {
  $('#btnOpenStudio').onclick = () => openStudio();
  $('#stClose').onclick = closeStudio;
  $('#stSet').addEventListener('change', async () => { studio.set = $('#stSet').value; studio.current = null; await loadSets(); });
  $('#stNewSet').onclick = studioNewSet;
  $('#stUseSet').onclick = () => {
    const s = studio.sets.find(x => x.name === studio.set);
    if (!s) return;
    state.config.templatesDir = s.dir;
    saveLocal(); bindFields(); loadSets();
    toast('Generation now uses the set "' + s.name + '"');
  };
  $('#stExport').onclick = async () => {
    if (!studio.set) return;
    try { download(await api('/api/studio/sets/' + encodeURIComponent(studio.set) + '/export'), 'specforge-templates-' + studio.set + '.zip'); } catch (e) { toast(e.message); }
  };
  $('#stImport').onclick = () => $('#stImportFile').click();
  $('#stImportFile').addEventListener('change', async ev => {
    const f = ev.target.files[0];
    ev.target.value = '';
    if (!f) return;
    const name = await askName('Import a template set', 'Import into the set named', f.name.replace(/\.zip$/i, '').replace(/^specforge-templates-/, '').replace(/[^A-Za-z0-9._-]/g, '-') || 'imported');
    if (!name) return;
    try {
      const res = await fetch('/api/studio/sets/' + encodeURIComponent(name) + '/import', { method: 'POST', headers: { 'X-SpecForge': '1', 'Content-Type': 'application/zip' }, body: await f.arrayBuffer() });
      const data = await res.json();
      if (!res.ok) throw new Error(data.message || data.error || res.statusText);
      studio.set = name;
      await loadSets();
      toast(data.imported + ' file(s) imported into "' + name + '"');
    } catch (e) { toast(e.message); }
  });
  $('#stDeleteSet').onclick = async () => {
    if (!studio.set || !confirm('Delete the template set "' + studio.set + '" and all its customized templates?')) return;
    try { await api('/api/studio/sets/' + encodeURIComponent(studio.set), { method: 'DELETE' }); studio.set = ''; studio.current = null; await loadSets(); } catch (e) { toast(e.message); }
  };
  $('#stSearch').addEventListener('input', renderStudioList);
  $('#stOnlyFlow').addEventListener('change', renderStudioList);
  $('#stCustomize').onclick = studioCustomize;
  $('#stSave').onclick = studioSave;
  $('#stReset').onclick = studioReset;
  $('#stCompare').onclick = () => {
    if (studio.dirty && !studio.compare && !confirm('Compare shows the default; your unsaved changes are kept. Continue?')) return;
    if (!studio.compare) studio.draftWhileComparing = studio.cm.getValue();
    studio.compare = !studio.compare;
    const dirty = studio.dirty;
    showTemplate();
    if (!studio.compare && studio.draftWhileComparing != null) { studio.cm.setValue(studio.draftWhileComparing); studio.dirty = dirty; $('#stDirty').hidden = !dirty; }
  };
  $('#stItem').addEventListener('change', () => { studio.item = $('#stItem').value; studioPreview(); });
  $('#stDiff').addEventListener('change', renderStudioOutput);
  $('#stVerify').onclick = studioVerify;
  $('#stVarSearch').addEventListener('input', () => renderVars((studio.lastPreview || {}).model || []));
  $('#btnCustomizeFile').onclick = async () => {
    const f = state.preview && state.preview.files.find(x => x.path === state.selected);
    if (!f) return;
    const list = await api('/api/templates?dir=' + encodeURIComponent(state.config.templatesDir || ''));
    const t = list.templates.find(x => x.id === f.id);
    openStudio(t ? t.template : undefined);
  };
  window.addEventListener('beforeunload', ev => { if (studio.dirty) { ev.preventDefault(); ev.returnValue = ''; } });
}

function wireDatabase() {
  $('#engineSearch').addEventListener('input', renderEngineTiles);
  $('#btnLeastPriv').onclick = showLeastPrivilege;
  $('#btnDrivers').onclick = showDrivers;
  $('#lpCopy').onclick = async () => {
    try { await navigator.clipboard.writeText($('#lpCode').textContent); toast('Copied'); } catch (e) { toast('Select the text and copy it'); }
  };
  ['#dbName', '#dbHost', '#dbPort', '#dbDatabase', '#dbPath', '#dbUser', '#dbPassword', '#dbPasswordEnv', '#dbSsl', '#dbUrl']
    .forEach(id => $(id).addEventListener('input', dbDirty));
  $('#btnDbTest').onclick = dbTest;
  $('#btnDbSave').onclick = dbSave;
  $('#btnDbRead').onclick = dbRead;
  $('#btnDbCancel').onclick = () => state.db.jobId && api('/api/jobs/' + state.db.jobId, { method: 'DELETE' });
  let searchTimer;
  $('#selSearch').addEventListener('input', () => { clearTimeout(searchTimer); searchTimer = setTimeout(renderSelect, 150); });
  $('#btnSelAll').onclick = () => { state.config.databasePlan.select = { include: [], exclude: [] }; planChanged(); renderSelect(); };
  $('#btnSelNone').onclick = () => { state.config.databasePlan.select = { include: ['(none)'], exclude: [] }; planChanged(); renderSelect(); };
  $('#btnSelBack').onclick = () => goStep('1');
  $('#btnSelNext').onclick = () => goStep('map');
  $('#btnMapBack').onclick = () => goStep('select');
  let mapTimer;
  $('#mapSearch').addEventListener('input', () => { clearTimeout(mapTimer); mapTimer = setTimeout(renderMap, 150); });
  $('#btnMapExpand').onclick = () => { state.db.openCards = new Set(state.db.planView.entities.map(e => e.table)); renderMap(); };
  $('#btnMapCollapse').onclick = () => { state.db.openCards = new Set(); renderMap(); };
  $('#ucNewForm').addEventListener('submit', ev => {
    ev.preventDefault();
    const name = $('#ucNewName').value.trim();
    if (!name) return $('#ucNewName').focus();
    controllersPlan().push({ name, path: null, operations: [] });
    $('#ucNewName').value = '';
    planChanged();
  });
  $('#btnMapNext').onclick = () => goStep('api');
  $('#btnApiBack').onclick = () => goStep('map');
  $('#btnApiNext').onclick = () => goStep('2');
}

function wire() {
  $$('.tab').forEach(t => t.addEventListener('click', () => setTab(t.dataset.tab)));
  $('#btnAnalyze').onclick = analyze;
  $('#btnSample').onclick = () => { $('#specText').value = SAMPLE; $('#specText').dispatchEvent(new Event('input')); };
  $('#specText').addEventListener('input', () => {
    const v = $('#specText').value.trim();
    $('#pasteInfo').textContent = v ? `${v.length.toLocaleString()} characters, looks like ${v.startsWith('{') ? 'JSON' : 'YAML'}` : ' ';
  });
  $('#specFile').addEventListener('change', e => readFile(e.target.files[0]));
  const dz = $('#dropZone');
  ['dragenter', 'dragover'].forEach(ev => dz.addEventListener(ev, e => { e.preventDefault(); dz.classList.add('over'); }));
  ['dragleave', 'drop'].forEach(ev => dz.addEventListener(ev, e => { e.preventDefault(); dz.classList.remove('over'); }));
  dz.addEventListener('drop', e => readFile(e.dataTransfer.files[0]));
  $('#specUrl').addEventListener('keydown', e => { if (e.key === 'Enter') analyze(); });

  $$('.step').forEach(b => b.addEventListener('click', () => !b.disabled && goStep(b.dataset.step)));
  $$('.choice[data-source]').forEach(c => c.addEventListener('click', () => setSource(c.dataset.source)));
  $$('.choice[data-preset]').forEach(c => c.addEventListener('click', () => applyStylePreset(c.dataset.preset)));
  $('#sourceSwitch').addEventListener('change', e => setSource(e.target.value));
  wireDatabase();
  $('#btnToStep2').onclick = () => goStep(2);
  $('#btnBack1').onclick = () => goStep(state.source === 'db' ? 'api' : '1');
  $('#btnToStep3').onclick = () => goStep(3);
  $('#btnBack2').onclick = () => goStep(2);
  $('#btnRefresh').onclick = runPreview;
  $('#btnWrite').onclick = writeFiles;
  $('#btnZip').onclick = downloadZip;
  $('#fileFilter').addEventListener('input', e => { state.filter = e.target.value; if (state.preview) renderTree(); });
  $('#btnCopy').onclick = async () => {
    const f = state.preview && state.preview.files.find(x => x.path === state.selected);
    if (!f) return;
    try { await navigator.clipboard.writeText(f.content); toast('Copied'); } catch (e) { toast('Copy failed: select the text manually'); }
  };
  $('#btnExportTemplates').onclick = exportTemplates;
  $('#btnReloadTemplates').onclick = loadTemplates;

  $('#btnProfileMenu').onclick = () => openProfileMenu($('#profileMenu').hidden, true);
  document.addEventListener('keydown', ev => {
    if (ev.key === 'Escape' && !$('#profileMenu').hidden) { openProfileMenu(false); $('#btnProfileMenu').focus(); }
  });
  $('#btnProfileMenu').addEventListener('keydown', ev => {
    if (ev.key === 'ArrowDown' || ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); openProfileMenu(true, true); }
  });
  $('#profileMenu').addEventListener('keydown', menuKeys);
  $('#profileMenu').addEventListener('click', ev => { if (ev.target.closest('[role=menuitem]')) openProfileMenu(false); });
  document.addEventListener('click', ev => { if (!ev.target.closest('.menu-wrap')) openProfileMenu(false); });
  $('#btnProfileSave').onclick = () => profileSave(false);
  $('#btnProfileSaveAs').onclick = () => profileSave(true);
  $('#btnProfileDelete').onclick = profileDelete;
  $('#btnProfileExport').onclick = () => download(new Blob([JSON.stringify(state.config, null, 2)], { type: 'application/json' }), (state.profile || 'specforge-profile') + '.json');
  $('#btnProfileImport').onclick = () => $('#profileFile').click();
  $('#profileFile').addEventListener('change', async e => {
    const f = e.target.files[0];
    if (!f) return;
    try {
      state.config = mergeDefaults(state.info.defaults, JSON.parse(await f.text()));
      saveLocal(); bindFields(); toast('Settings imported');
    } catch (err) { toast('Not a valid settings file: ' + err.message); }
    e.target.value = '';
  });
  $('#btnTheme').onclick = toggleTheme;
  wireStudio();
}

async function init() {
  applyTheme();
  wire();
  try {
    state.info = await api('/api/info');
  } catch (e) {
    const box = $('#analysisError');
    box.textContent = 'Cannot reach the SpecForge server: ' + e.message;
    box.hidden = false;
    return;
  }
  $('#version').textContent = 'v' + state.info.version;
  try { state.spring = await api('/api/spring/metadata'); } catch (e) { state.spring = { lines: [] }; }
  state.config = mergeDefaults(state.info.defaults, loadLocal());
  fillBootPickers();
  let remembered = null;
  try { remembered = localStorage.getItem(SOURCE_KEY); } catch (e) { /* storage unavailable */ }
  if (remembered === 'spec' || remembered === 'db') setSource(remembered);
  buildLayersTable();
  buildNaming();
  bindFields();
  await refreshProfiles();
}

init();
