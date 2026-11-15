/* Apply the existing site's theme preference before first paint. */
(() => {
  document.documentElement.classList.add('js');
  if (typeof HTMLDialogElement !== 'undefined' && 'showModal' in HTMLDialogElement.prototype) document.documentElement.classList.add('js-drawer');
  let theme;
  try { theme = localStorage.getItem('theme'); } catch (_) { /* Storage can be disabled. */ }
  if (theme !== 'light' && theme !== 'dark') {
    theme = matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
  }
  document.documentElement.dataset.theme = theme;
  try { if(location.pathname==='/' && localStorage.getItem('reclazz-lang')==='tr'){
    document.documentElement.classList.add('lang-loading');
    setTimeout(() => document.documentElement.classList.remove('lang-loading'),300);
  }} catch (_) {}
})();
