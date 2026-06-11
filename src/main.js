import { Capacitor } from '@capacitor/core';
import { listFiles, FileAccess, importImages } from './filesystem.js';
import { openEditor } from './editor.js';

const IMAGE_EXT = /\.(gif|jpg|jpeg|png|webp|bmp)$/i;

// ── DOM refs ──────────────────────────────────────────────────────────────────
const grid               = document.getElementById('grid');
const emptyState         = document.getElementById('empty-state');
const emptyMsg           = document.getElementById('empty-msg');
const toast              = document.getElementById('toast');
const btnChange          = document.getElementById('btn-change-folder');
const btnChangeBubble    = document.getElementById('btn-change-bubble-icon');
const cfgFolderPath      = document.getElementById('cfg-folder-path');
const cfgImageCount      = document.getElementById('cfg-image-count');
const bubbleIconPreview  = document.getElementById('bubble-icon-preview');
const tabs               = document.querySelectorAll('.tab');
const pages              = document.querySelectorAll('.page');
const headerTitle        = document.getElementById('header-title');
const selectionBar       = document.getElementById('selection-bar');
const selCount           = document.getElementById('sel-count');
const btnCancelSel       = document.getElementById('btn-cancel-sel');
const btnDeleteSel       = document.getElementById('btn-delete-sel');
const btnAddImg          = document.getElementById('btn-add-img');
const cfgCropW           = document.getElementById('cfg-crop-w');
const cfgCropH           = document.getElementById('cfg-crop-h');

// ── State ─────────────────────────────────────────────────────────────────────
let selectedFolder  = localStorage.getItem('allgif_folder');
let bubbleIconPath  = localStorage.getItem('allgif_bubble_icon');
let imageCount      = 0;
let toastTimer      = null;
let lazyObserver    = null;
let selectionMode   = false;
let selectedPaths   = new Set();
let editTarget      = null;   // { file, cell } — image chosen for editing


// ── Tabs ──────────────────────────────────────────────────────────────────────
tabs.forEach(tab => {
  tab.addEventListener('click', () => {
    if (tab.dataset.page === 'studio') {
      if (!editTarget) { showToast('Select an image in Gallery first'); return; }
      openEditor(editTarget.file, () => {
        loadImages(selectedFolder);
        switchTab('gallery');
      });
      return;
    }
    switchTab(tab.dataset.page);
  });
});

function switchTab(page) {
  tabs.forEach(t => t.classList.remove('active'));
  pages.forEach(p => p.classList.remove('active'));
  document.querySelector(`.tab[data-page="${page}"]`).classList.add('active');
  document.getElementById('page-' + page).classList.add('active');
}

// ── Init ──────────────────────────────────────────────────────────────────────
if (selectedFolder) {
  FileAccess.syncFolder({ path: selectedFolder }).catch(() => {});
  updateConfigUI(selectedFolder);
  loadImages(selectedFolder);
  btnAddImg.classList.remove('hidden');
}

if (bubbleIconPath) updateBubbleIconPreview(bubbleIconPath);

cfgCropW.value = localStorage.getItem('allgif_crop_w') || '';
cfgCropH.value = localStorage.getItem('allgif_crop_h') || '';

cfgCropW.addEventListener('input', () => {
  localStorage.setItem('allgif_crop_w', cfgCropW.value);
});
cfgCropH.addEventListener('input', () => {
  localStorage.setItem('allgif_crop_h', cfgCropH.value);
});

btnChange.addEventListener('click', openFolderPicker);
btnChangeBubble.addEventListener('click', openBubbleIconPicker);
btnCancelSel.addEventListener('click', exitSelectionMode);
btnDeleteSel.addEventListener('click', deleteSelected);
btnAddImg.addEventListener('click', addImages);

// ── Image grid ────────────────────────────────────────────────────────────────
async function loadImages(path) {
  grid.innerHTML = '';
  editTarget = null;
  if (lazyObserver) { lazyObserver.disconnect(); lazyObserver = null; }
  showEmpty('Loading…');

  try {
    const files = await listFiles(path);
    const images = files
      .filter(f => !f.isDirectory && IMAGE_EXT.test(f.name))
      .sort((a, b) => b.lastModified - a.lastModified);

    imageCount = images.length;
    cfgImageCount.textContent = imageCount === 0 ? 'No images' : `${imageCount} image${imageCount !== 1 ? 's' : ''}`;

    if (images.length === 0) {
      showEmpty('No images found in this folder');
      return;
    }

    hideEmpty();

    lazyObserver = new IntersectionObserver(entries => {
      entries.forEach(e => {
        if (e.isIntersecting) {
          const img = e.target;
          if (img.dataset.src) {
            img.src = img.dataset.src;
            delete img.dataset.src;
            lazyObserver.unobserve(img);
          }
        }
      });
    }, { rootMargin: '300px' });

    images.forEach(file => {
      const cell = document.createElement('div');
      cell.className = 'grid-cell';
      const img = document.createElement('img');
      img.dataset.src = Capacitor.convertFileSrc(file.path);
      img.alt = file.name;
      img.decoding = 'async';
      lazyObserver.observe(img);
      cell.appendChild(img);

      // Long-press → enter selection mode
      cell.addEventListener('contextmenu', e => {
        e.preventDefault();
        if (!selectionMode) enterSelectionMode();
        toggleSelect(file.path, cell);
      });

      cell.addEventListener('click', () => {
        if (selectionMode) {
          toggleSelect(file.path, cell);
        } else {
          selectForEdit(file, cell);
        }
      });

      grid.appendChild(cell);
    });

  } catch (err) {
    showEmpty('Error: ' + err.message);
  }
}

async function copyImage(path, cell) {
  try {
    cell.classList.add('flash');
    const cropW = parseInt(cfgCropW.value) || 0;
    const cropH = parseInt(cfgCropH.value) || 0;
    const opts = { path };
    if (cropW > 0 && cropH > 0) {
      opts.cropWidth = cropW;
      opts.cropHeight = cropH;
    }
    await FileAccess.copyImageToClipboard(opts);
    showToast('Copied to clipboard!');
  } catch (err) {
    showToast('Copy failed: ' + err.message);
  } finally {
    setTimeout(() => cell.classList.remove('flash'), 400);
  }
}

function selectForEdit(file, cell) {
  if (editTarget?.cell) editTarget.cell.classList.remove('edit-target');
  editTarget = { file, cell };
  cell.classList.add('edit-target');
  showToast('Tap Edit tab to open studio');
}

// ── Selection mode ────────────────────────────────────────────────────────────
function enterSelectionMode() {
  selectionMode = true;
  headerTitle.classList.add('hidden');
  selectionBar.classList.remove('hidden');
  btnAddImg.classList.add('hidden');
  updateSelectionUI();
}

function exitSelectionMode() {
  selectionMode = false;
  selectedPaths.clear();
  headerTitle.classList.remove('hidden');
  selectionBar.classList.add('hidden');
  if (selectedFolder) btnAddImg.classList.remove('hidden');
  document.querySelectorAll('.grid-cell.selected')
    .forEach(c => c.classList.remove('selected'));
}

function toggleSelect(path, cell) {
  if (selectedPaths.has(path)) {
    selectedPaths.delete(path);
    cell.classList.remove('selected');
  } else {
    selectedPaths.add(path);
    cell.classList.add('selected');
  }
  updateSelectionUI();
  if (selectedPaths.size === 0) exitSelectionMode();
}

function updateSelectionUI() {
  const n = selectedPaths.size;
  selCount.textContent = n === 1 ? '1 selected' : `${n} selected`;
  btnDeleteSel.disabled = n === 0;
}

// ── Delete selected ───────────────────────────────────────────────────────────
async function deleteSelected() {
  const paths = [...selectedPaths];
  if (paths.length === 0) return;
  let failed = 0;
  for (const path of paths) {
    try {
      await FileAccess.deleteFile({ path });
    } catch {
      failed++;
    }
  }
  exitSelectionMode();
  await loadImages(selectedFolder);
  showToast(failed === 0
    ? `Deleted ${paths.length} image${paths.length !== 1 ? 's' : ''}`
    : `Deleted ${paths.length - failed}, ${failed} failed`);
}

// ── Add images ────────────────────────────────────────────────────────────────
async function addImages() {
  if (!selectedFolder) return;
  try {
    const imported = await importImages(selectedFolder);
    if (!imported || imported.length === 0) return;
    await loadImages(selectedFolder);
    showToast(`Added ${imported.length} image${imported.length !== 1 ? 's' : ''}`);
  } catch (err) {
    if (err.message !== 'Cancelled') showToast('Could not import images');
  }
}

// ── Folder picker ─────────────────────────────────────────────────────────────
async function openFolderPicker() {
  try {
    const { path } = await FileAccess.pickFolder();
    if (!path) return;
    selectedFolder = path;
    localStorage.setItem('allgif_folder', selectedFolder);
    FileAccess.syncFolder({ path: selectedFolder }).catch(() => {});
    updateConfigUI(selectedFolder);
    loadImages(selectedFolder);
    btnAddImg.classList.remove('hidden');
    switchTab('gallery');
  } catch (err) {
    if (err.message !== 'Cancelled') showToast('Could not open folder picker');
  }
}

// ── Bubble icon picker ────────────────────────────────────────────────────────
async function openBubbleIconPicker() {
  try {
    const { path } = await FileAccess.pickBubbleIcon();
    if (!path) return;
    bubbleIconPath = path;
    localStorage.setItem('allgif_bubble_icon', path);
    updateBubbleIconPreview(path);
    showToast('Bubble icon updated!');
  } catch (err) {
    if (err.message !== 'Cancelled') showToast('Could not pick image');
  }
}

function updateBubbleIconPreview(path) {
  bubbleIconPreview.src = Capacitor.convertFileSrc(path);
  bubbleIconPreview.classList.add('loaded');
}

// ── Helpers ───────────────────────────────────────────────────────────────────
function updateConfigUI(path) {
  cfgFolderPath.textContent = path;
  cfgFolderPath.classList.remove('muted');
}

function showEmpty(msg) {
  emptyMsg.textContent = msg;
  emptyState.classList.remove('hidden');
  grid.style.display = 'none';
}

function hideEmpty() {
  emptyState.classList.add('hidden');
  grid.style.display = '';
}

function showToast(msg) {
  toast.textContent = msg;
  toast.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove('show'), 2200);
}
