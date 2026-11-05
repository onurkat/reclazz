/* Apply the existing site's theme preference before first paint. */
(() => {
  let theme;
  try { theme = localStorage.getItem('theme'); } catch (_) { /* Storage can be disabled. */ }
  if (theme !== 'light' && theme !== 'dark') {
    theme = matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
  }
  document.documentElement.dataset.theme = theme;
})();
