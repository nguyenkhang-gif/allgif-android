import { Capacitor } from '@capacitor/core';
import { FileAccess } from './filesystem.js';

// ── State ──────────────────────────────────────────────────────────────────────
let currentFile  = null;
let baseImg      = null;
let editedImg    = null;
let imgRect      = { x: 0, y: 0, w: 0, h: 0 };
let cropRect     = { x: 0, y: 0, w: 0, h: 0 };
let outputScale  = 1.0;   // 0.1 – 1.0, applied only when saving/copying
let onCloseCallback = null;

// ── DOM (lazy) ─────────────────────────────────────────────────────────────────
let modal, canvas, ctx, loadingEl, sizeSlider, sizeLabel;
let cropHandles = {};
let cropMover;
let domReady = false;

const HANDLE  = 28;   // touch target px
const MIN_DIM = 50;   // min crop px

// ── Public API ─────────────────────────────────────────────────────────────────
export function openEditor(file, closeCallback) {
  currentFile     = file;
  onCloseCallback = closeCallback;
  editedImg    = null;
  outputScale  = 1.0;

  initDom();
  modal.classList.remove('hidden');
  document.getElementById('editor-filename').textContent = file.name;
  sizeSlider.value = '100';
  sizeLabel.textContent = '100%';
  setLoading(false);

  baseImg = new Image();
  baseImg.onload = fitAndDraw;
  baseImg.src = Capacitor.convertFileSrc(file.path);
}

// ── DOM init ───────────────────────────────────────────────────────────────────
function initDom() {
  if (domReady) return;
  domReady = true;

  modal      = document.getElementById('editor-modal');
  canvas     = document.getElementById('editor-canvas');
  ctx        = canvas.getContext('2d');
  loadingEl  = document.getElementById('editor-loading');
  sizeSlider = document.getElementById('zoom-slider');
  sizeLabel  = document.getElementById('zoom-label');

  sizeSlider.addEventListener('input', () => {
    outputScale = parseInt(sizeSlider.value) / 100;
    sizeLabel.textContent = sizeSlider.value + '%';
  });

  cropHandles.tl = document.getElementById('crop-tl');
  cropHandles.tr = document.getElementById('crop-tr');
  cropHandles.bl = document.getElementById('crop-bl');
  cropHandles.br = document.getElementById('crop-br');
  cropMover      = document.getElementById('crop-mover');

  document.getElementById('btn-editor-cancel').addEventListener('click', closeEditor);
  document.getElementById('btn-editor-save').addEventListener('click', doSave);
  document.getElementById('btn-remove-bg').addEventListener('click', doRemoveBg);
  document.getElementById('btn-editor-copy').addEventListener('click', doCopy);

  for (const [id, el] of Object.entries(cropHandles)) {
    el.addEventListener('touchstart', e => startHandleDrag(e, id), { passive: false });
  }
  cropMover.addEventListener('touchstart', startMoveDrag, { passive: false });
  document.addEventListener('touchmove', onDragMove, { passive: false });
  document.addEventListener('touchend', stopDrag);
}

// ── Canvas ─────────────────────────────────────────────────────────────────────
function fitAndDraw() {
  const wrap = canvas.parentElement;
  const cw   = wrap.clientWidth;
  const ch   = wrap.clientHeight;
  canvas.width  = cw;
  canvas.height = ch;

  const src   = editedImg || baseImg;
  const scale = Math.min(cw / src.naturalWidth, ch / src.naturalHeight);
  imgRect = {
    x: (cw - src.naturalWidth  * scale) / 2,
    y: (ch - src.naturalHeight * scale) / 2,
    w: src.naturalWidth  * scale,
    h: src.naturalHeight * scale,
  };
  cropRect = { ...imgRect };
  draw();
  positionHandles();
}

function draw() {
  const src = editedImg || baseImg;
  ctx.clearRect(0, 0, canvas.width, canvas.height);

  if (editedImg) drawCheckerboard();
  ctx.drawImage(src, imgRect.x, imgRect.y, imgRect.w, imgRect.h);

  // Dark overlay around crop rect (4 strips)
  const { x, y, w, h } = cropRect;
  ctx.fillStyle = 'rgba(0,0,0,0.55)';
  ctx.fillRect(0,       0,           canvas.width,      y);
  ctx.fillRect(0,       y + h,       canvas.width,      canvas.height - y - h);
  ctx.fillRect(0,       y,           x,                 h);
  ctx.fillRect(x + w,   y,           canvas.width - x - w, h);

  // Crop border
  ctx.strokeStyle = '#fff';
  ctx.lineWidth = 1.5;
  ctx.strokeRect(x, y, w, h);

  // Rule-of-thirds grid
  ctx.strokeStyle = 'rgba(255,255,255,0.25)';
  ctx.lineWidth = 0.5;
  ctx.beginPath();
  for (let i = 1; i < 3; i++) {
    ctx.moveTo(x + w * i / 3, y); ctx.lineTo(x + w * i / 3, y + h);
    ctx.moveTo(x, y + h * i / 3); ctx.lineTo(x + w, y + h * i / 3);
  }
  ctx.stroke();
}

function drawCheckerboard() {
  const s = 12;
  for (let py = imgRect.y; py < imgRect.y + imgRect.h; py += s) {
    for (let px = imgRect.x; px < imgRect.x + imgRect.w; px += s) {
      const even = (Math.floor((px - imgRect.x) / s) + Math.floor((py - imgRect.y) / s)) % 2 === 0;
      ctx.fillStyle = even ? '#ccc' : '#eee';
      ctx.fillRect(px, py, Math.min(s, imgRect.x + imgRect.w - px), Math.min(s, imgRect.y + imgRect.h - py));
    }
  }
}

function positionHandles() {
  const { x, y, w, h } = cropRect;
  const hs = HANDLE / 2;
  const pos = { tl: [x - hs, y - hs], tr: [x + w - hs, y - hs], bl: [x - hs, y + h - hs], br: [x + w - hs, y + h - hs] };
  for (const [id, [l, t]] of Object.entries(pos)) {
    cropHandles[id].style.left = l + 'px';
    cropHandles[id].style.top  = t + 'px';
  }
  cropMover.style.left   = (x + HANDLE)     + 'px';
  cropMover.style.top    = (y + HANDLE)     + 'px';
  cropMover.style.width  = (w - HANDLE * 2) + 'px';
  cropMover.style.height = (h - HANDLE * 2) + 'px';
}

// ── Drag ───────────────────────────────────────────────────────────────────────
let dragType = null;
let dragHandle = null;
let dragStart = null;

function startHandleDrag(e, id) {
  e.preventDefault();
  dragType   = 'handle';
  dragHandle = id;
  dragStart  = { x: e.touches[0].clientX, y: e.touches[0].clientY, rect: { ...cropRect } };
}

function startMoveDrag(e) {
  e.preventDefault();
  dragType  = 'move';
  dragStart = { x: e.touches[0].clientX, y: e.touches[0].clientY, rect: { ...cropRect } };
}

function onDragMove(e) {
  if (!dragType) return;
  e.preventDefault();
  const dx = e.touches[0].clientX - dragStart.x;
  const dy = e.touches[0].clientY - dragStart.y;
  const r  = dragStart.rect;

  const im = imgRect;
  let { x, y, w, h } = r;

  if (dragType === 'move') {
    x = clamp(r.x + dx, im.x, im.x + im.w - r.w);
    y = clamp(r.y + dy, im.y, im.y + im.h - r.h);
  } else {
    switch (dragHandle) {
      case 'tl':
        x = clamp(r.x + dx, im.x,          r.x + r.w - MIN_DIM);
        y = clamp(r.y + dy, im.y,          r.y + r.h - MIN_DIM);
        w = r.x + r.w - x; h = r.y + r.h - y;
        break;
      case 'tr':
        y = clamp(r.y + dy, im.y,          r.y + r.h - MIN_DIM);
        w = clamp(r.w + dx, MIN_DIM,       im.x + im.w - r.x);
        h = r.y + r.h - y;
        break;
      case 'bl':
        x = clamp(r.x + dx, im.x,          r.x + r.w - MIN_DIM);
        w = r.x + r.w - x;
        h = clamp(r.h + dy, MIN_DIM,       im.y + im.h - r.y);
        break;
      case 'br':
        w = clamp(r.w + dx, MIN_DIM, im.x + im.w - r.x);
        h = clamp(r.h + dy, MIN_DIM, im.y + im.h - r.y);
        break;
    }
  }

  cropRect = { x, y, w, h };
  draw();
  positionHandles();
}

function stopDrag() { dragType = null; dragStart = null; }
function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

// ── Actions ────────────────────────────────────────────────────────────────────
async function doRemoveBg() {
  setLoading(true, 'Removing background…');
  try {
    const { data } = await FileAccess.removeBackground({ path: currentFile.path });
    const img = new Image();
    await new Promise((res, rej) => { img.onload = res; img.onerror = rej; img.src = 'data:image/png;base64,' + data; });
    editedImg = img;
    fitAndDraw();
    editorToast('Background removed!');
  } catch (err) {
    editorToast('Failed: ' + err.message);
  } finally {
    setLoading(false);
  }
}

async function doSave() {
  setLoading(true, 'Saving…');
  try {
    const { base64, type } = await getCroppedData();
    let savePath = currentFile.path;
    if (editedImg && !/\.png$/i.test(savePath)) {
      savePath = savePath.replace(/\.[^.]+$/, '.png');
    }
    await FileAccess.saveImage({ path: savePath, data: base64 });
    editorToast('Saved!');
    setTimeout(() => { closeEditor(); onCloseCallback && onCloseCallback(); }, 900);
  } catch (err) {
    editorToast('Save failed: ' + err.message);
    setLoading(false);
  }
}

async function doCopy() {
  setLoading(true, 'Copying…');
  try {
    const { base64 } = await getCroppedData();
    await FileAccess.copyImageDataToClipboard({ data: base64 });
    editorToast('Copied!');
  } catch (err) {
    editorToast('Copy failed: ' + err.message);
  } finally {
    setLoading(false);
  }
}

async function getCroppedData() {
  const src    = editedImg || baseImg;
  const scaleX = src.naturalWidth  / imgRect.w;
  const scaleY = src.naturalHeight / imgRect.h;
  const sx = Math.round((cropRect.x - imgRect.x) * scaleX);
  const sy = Math.round((cropRect.y - imgRect.y) * scaleY);
  const sw = Math.round(cropRect.w * scaleX);
  const sh = Math.round(cropRect.h * scaleY);

  const outW  = Math.max(1, Math.round(sw * outputScale));
  const outH  = Math.max(1, Math.round(sh * outputScale));
  const oc    = new OffscreenCanvas(outW, outH);
  const octx  = oc.getContext('2d');
  octx.drawImage(src, sx, sy, sw, sh, 0, 0, outW, outH);

  const type = editedImg ? 'image/png' : 'image/jpeg';
  const blob = await oc.convertToBlob({ type, quality: 0.95 });
  const base64 = await new Promise((res, rej) => {
    const reader = new FileReader();
    reader.onload  = () => res(reader.result.split(',')[1]);
    reader.onerror = rej;
    reader.readAsDataURL(blob);
  });
  return { base64, type };
}

// ── Helpers ────────────────────────────────────────────────────────────────────
function closeEditor() {
  modal.classList.add('hidden');
  editedImg = null;
}

function setLoading(show, msg = '') {
  loadingEl.querySelector('span').textContent = msg;
  loadingEl.classList.toggle('hidden', !show);
  ['btn-editor-save', 'btn-remove-bg', 'btn-editor-copy'].forEach(id => {
    document.getElementById(id).disabled = show;
  });
}

let toastTimer = null;
function editorToast(msg) {
  const el = document.getElementById('editor-toast');
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 2200);
}
