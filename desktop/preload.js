// Arayüze sadece cihaz listesi için gereken üç işlev açılır
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('kumandaDesktop', {
  onDevices: cb => { ipcRenderer.on('bt-devices', (e, list) => cb(list)); },
  pick: id => ipcRenderer.send('bt-pick', String(id)),
  cancel: () => ipcRenderer.send('bt-cancel'),
});
