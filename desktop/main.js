// Drift Kumanda — Windows masaüstü uygulaması (Electron)
// Kumanda arayüzü web/index.html. Bluetooth cihaz seçimi tarayıcı penceresi yerine
// uygulamanın kendi listesinde yapılır: Electron'un "select-bluetooth-device" olayı
// bulunan cihazları arayüze gönderir, kullanıcının seçtiği cihaz geri bildirilir.
const { app, BrowserWindow, ipcMain, shell, session } = require('electron');
const path = require('path');

let win = null;
let btCallback = null;

function finishBluetooth(id) {
  if (!btCallback) return;
  const cb = btCallback;
  btCallback = null;
  try { cb(id); } catch (e) { /* istek zaten bitti */ }
}

function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 720,
    minWidth: 760,
    minHeight: 420,
    backgroundColor: '#11141b',
    title: 'Drift Kumanda',
    icon: path.join(__dirname, 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  win.removeMenu();

  win.webContents.on('select-bluetooth-device', (event, deviceList, callback) => {
    event.preventDefault();
    btCallback = callback;
    win.webContents.send('bt-devices', deviceList.map(d => ({ id: d.deviceId, name: d.deviceName || '' })));
  });

  // Dış bağlantılar (GitHub sürüm sayfası vb.) varsayılan tarayıcıda açılsın
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/i.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith('file:')) { e.preventDefault(); if (/^https?:/i.test(url)) shell.openExternal(url); }
  });

  win.on('closed', () => { win = null; btCallback = null; });
  win.loadFile(path.join(__dirname, 'web', 'index.html'));
}

ipcMain.on('bt-pick', (e, id) => finishBluetooth(String(id || '')));
ipcMain.on('bt-cancel', () => finishBluetooth(''));

app.whenReady().then(() => {
  // Eşleştirme isteyen cihazlarda onay penceresi yerine otomatik onay (robotların çoğu istemez)
  if (typeof session.defaultSession.setBluetoothPairingHandler === 'function') {
    session.defaultSession.setBluetoothPairingHandler((details, callback) => {
      if (details.pairingKind === 'confirm') callback({ confirmed: true });
      else if (details.pairingKind === 'confirmPin') callback({ confirmed: true });
      else callback({ confirmed: false });
    });
  }
  createWindow();
});

app.on('window-all-closed', () => app.quit());
