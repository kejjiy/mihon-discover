const { contextBridge, ipcRenderer } = require('electron');
const call = channel => (...args) => ipcRenderer.invoke(channel, ...args);
const listen = channel => callback => { const handler = (_event, data) => callback(data); ipcRenderer.on(channel, handler); return () => ipcRenderer.removeListener(channel, handler); };
contextBridge.exposeInMainWorld('mihon', {
  state: call('state'), toggleLan: call('lan-toggle'), manual: call('lan-manual'), pair: call('lan-pair'), approve: call('lan-approve'), forget: call('lan-forget'), sync: call('lan-sync'), syncAll: call('lan-sync-all'),
  import: call('import'), openChapter: call('chapter-open'), page: call('page'), download: call('download'), progress: call('progress'), edit: call('edit'), feedback: call('feedback'),
  catalogue: call('catalogue'), cachedCatalogue: call('cached-catalogue'), recommend: call('recommend'), settings: call('settings'), fullscreen: call('fullscreen'), export: call('export'), importSnapshot: call('import-snapshot'),
  onState: listen('state'), onNotice: listen('notice'), onDownload: listen('download-progress')
});
