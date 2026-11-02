// Applies the saved/system dark theme before first paint. A file rather than an inline script so the
// Content-Security-Policy (web/nginx.conf) can stay at script-src 'self'.
(function () {
  var theme = null;
  try { theme = window.localStorage.getItem('mp-theme'); } catch (e) { theme = null; }
  var dark = theme === 'dark' || ((theme === null || theme === 'system') && window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
  if (dark) document.documentElement.classList.add('dark');
})();
