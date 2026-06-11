// popup.js — plain JS, no module, runs in a plain WebView with Android bridge

const Native = window.Android || {
  closePopup:  () => {},
  removeBubble: () => {},
  openApp:     () => {},
  getFolder:   () => '',
  listImages:  () => '[]',
  copyImage:   () => 'ok',
};

const grid      = document.getElementById('grid');
const empty     = document.getElementById('empty');
const emptyMsg  = document.getElementById('empty-msg');
const toast     = document.getElementById('toast');
let toastTimer  = null;

document.getElementById('btn-minimize').addEventListener('click', () => Native.closePopup());
document.getElementById('btn-close').addEventListener('click',    () => Native.removeBubble());
document.getElementById('btn-open-app').addEventListener('click', () => Native.openApp());

// ── Load images ───────────────────────────────────────────────────────────────
function init() {
  const folder = Native.getFolder();
  if (!folder) {
    showEmpty('Open AllGif and pick a folder first');
    return;
  }

  let images;
  try { images = JSON.parse(Native.listImages(folder)); } catch(e) { images = []; }

  if (images.length === 0) {
    showEmpty('No images in selected folder');
    return;
  }

  empty.classList.remove('show');
  grid.innerHTML = '';

  images.forEach(file => {
    const cell = document.createElement('div');
    cell.className = 'cell';

    const img = document.createElement('img');
    img.src = 'file://' + file.path;
    img.alt = file.name;
    img.decoding = 'async';
    img.onerror = () => { cell.style.display = 'none'; };

    cell.appendChild(img);
    cell.addEventListener('click', () => {
      const result = Native.copyImage(file.path);
      cell.classList.add('flash');
      setTimeout(() => cell.classList.remove('flash'), 350);
      showToast(result === 'ok' ? 'Copied!' : 'Failed');
    });

    grid.appendChild(cell);
  });
}

function showEmpty(msg) {
  emptyMsg.textContent = msg;
  empty.classList.add('show');
  grid.innerHTML = '';
}

function showToast(msg) {
  toast.textContent = msg;
  toast.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove('show'), 1800);
}

init();
