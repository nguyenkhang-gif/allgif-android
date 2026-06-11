import { registerPlugin } from '@capacitor/core';

// Native plugin — falls back to stubs in browser
export const FileAccess = registerPlugin('FileAccess', {
  web: {
    async listFiles({ path = '/' } = {}) {
      console.log('[FileAccess web stub] listFiles:', path);
      return { files: [] };
    },
    async readFile({ path, encoding = 'base64' } = {}) {
      console.log('[FileAccess web stub] readFile:', path, encoding);
      return { data: '' };
    },
    async getStorageRoots() {
      return { roots: [{ name: 'Internal Storage', path: '/sdcard' }] };
    },
    async pickFolder() {
      return { path: '/sdcard' };
    },
    async syncFolder() {},
    async pickBubbleIcon() { return { path: '' }; },
    async deleteFile({ path } = {}) {
      console.log('[FileAccess web stub] deleteFile:', path);
    },
    async importImages({ destFolder } = {}) {
      console.log('[FileAccess web stub] importImages:', destFolder);
      return { imported: [] };
    },
    async removeBackground({ path } = {}) {
      console.log('[FileAccess web stub] removeBackground:', path);
      return { data: '' };
    },
    async saveImage({ path, data } = {}) {
      console.log('[FileAccess web stub] saveImage:', path);
    },
    async copyImageDataToClipboard({ data } = {}) {
      console.log('[FileAccess web stub] copyImageDataToClipboard');
    },
  },
});

/**
 * List files in a directory.
 * @param {string} path  Absolute path, e.g. '/sdcard/DCIM'
 * @returns {Promise<Array<{name, path, isDirectory, size, lastModified}>>}
 */
export async function listFiles(path) {
  const { files } = await FileAccess.listFiles({ path });
  return files;
}

/**
 * Read a file's content.
 * @param {string} path      Absolute path to the file
 * @param {string} encoding  'base64' (default) or 'utf8'
 * @returns {Promise<string>}
 */
export async function readFile(path, encoding = 'base64') {
  const { data } = await FileAccess.readFile({ path, encoding });
  return data;
}

/**
 * Get storage root paths (Internal Storage, SD card, etc.)
 * @returns {Promise<Array<{name, path}>>}
 */
export async function getStorageRoots() {
  const { roots } = await FileAccess.getStorageRoots();
  return roots;
}

/**
 * Import one or more images from the system picker into destFolder.
 * @param {string} destFolder  Absolute path of the destination directory
 * @returns {Promise<Array<{name, path}>>}
 */
export async function importImages(destFolder) {
  const { imported } = await FileAccess.importImages({ destFolder });
  return imported;
}
