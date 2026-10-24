/**
 * Select a compiled UI from the device preference at the root or a known legacy
 * preview route. No browser-language inference or gameplay request occurs.
 * Storage denial and unsupported values fall back to English; the destination
 * Settings screen displays the existing localized preference notice. Navigation
 * errors leave the explicit language links available instead of losing input.
 */
(() => {
  let locale = 'en';
  try {
    const saved = localStorage.getItem('engine.ui-locale.v1');
    // Persisted bytes can select only a reviewed prefix, never a URL or origin.
    if (typeof saved === 'string' && /^(en|it)(?:-[a-z0-9]{1,8})*$/i.test(saved)) {
      locale = saved.slice(0, 2).toLowerCase();
    }
  } catch { /* English remains available when device storage is denied. */ }
  try {
    // replace avoids an extra back-button entry; opaque query/hash survive intact.
    location.replace('/' + locale + location.pathname + location.search + location.hash);
  } catch { /* Native links remain the recovery path if navigation is refused. */ }
})();
