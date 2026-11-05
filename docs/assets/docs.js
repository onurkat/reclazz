/* Progressive enhancement: every guide and matrix entry is present in the HTML. */
(() => {
  const root = document.documentElement;
  const themeButton = document.getElementById('theme-toggle');
  themeButton.hidden = false;
  const describeTheme = () => {
    themeButton.setAttribute('aria-label', `Switch to ${root.dataset.theme === 'dark' ? 'light' : 'dark'} theme`);
    themeButton.setAttribute('title', themeButton.getAttribute('aria-label'));
  };
  describeTheme();
  themeButton.addEventListener('click', () => {
    root.dataset.theme = root.dataset.theme === 'dark' ? 'light' : 'dark';
    try { localStorage.setItem('theme', root.dataset.theme); } catch (_) { /* Keep the in-page choice. */ }
    describeTheme();
  });

  document.querySelectorAll('.code-block').forEach(block => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'copy-button';
    button.textContent = 'Copy';
    button.setAttribute('aria-label', `Copy ${block.querySelector('.code-label').textContent} example`);
    block.append(button);
    button.addEventListener('click', async () => {
      const text = block.querySelector('code').textContent;
      try {
        if (!navigator.clipboard || !window.isSecureContext) throw new Error('Clipboard is unavailable');
        await navigator.clipboard.writeText(text);
        button.textContent = 'Copied';
        document.getElementById('copy-status').textContent = 'Example copied to clipboard.';
      } catch (_) {
        const selection = getSelection();
        const range = document.createRange();
        range.selectNodeContents(block.querySelector('code'));
        selection.removeAllRanges();
        selection.addRange(range);
        button.textContent = 'Selected';
        document.getElementById('copy-status').textContent = 'Clipboard is unavailable. The example is selected; use your keyboard to copy.';
      }
      setTimeout(() => { button.textContent = 'Copy'; }, 2200);
    });
  });

  const matrixForm = document.querySelector('.matrix-controls');
  if (matrixForm) {
    matrixForm.hidden = false;
    const query = document.getElementById('matrix-query');
    const category = document.getElementById('matrix-category');
    const status = document.getElementById('matrix-status');
    const rows = [...document.querySelectorAll('.matrix-table tbody tr')];
    const count = document.getElementById('matrix-count');
    const params = new URLSearchParams(location.search);
    query.value = params.get('q') || '';
    if ([...category.options].some(o => o.value === params.get('area'))) category.value = params.get('area');
    if ([...status.options].some(o => o.value === params.get('outcome'))) status.value = params.get('outcome');
    const apply = () => {
      const terms = query.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
      let visible = 0;
      for (const row of rows) {
        const text = row.textContent.toLowerCase();
        const matched = terms.every(term => text.includes(term)) && (!category.value || row.dataset.category === category.value) && (!status.value || row.dataset.status === status.value);
        row.hidden = !matched;
        if (matched) visible++;
      }
      count.textContent = `Showing ${visible} of ${rows.length} changes`;
      document.getElementById('matrix-empty').hidden = visible !== 0;
      const url = new URL(location.href);
      for (const [key, value] of [['q', query.value], ['area', category.value], ['outcome', status.value]]) {
        if (value) url.searchParams.set(key, value); else url.searchParams.delete(key);
      }
      try { history.replaceState(null, '', url); } catch (_) { /* Readable even without history permission. */ }
    };
    matrixForm.addEventListener('submit', e => e.preventDefault());
    matrixForm.addEventListener('input', apply);
    matrixForm.addEventListener('change', apply);
    matrixForm.addEventListener('reset', () => setTimeout(apply, 0));
    // A deep-linked record stays discoverable even if a shared filter excludes it.
    const revealHash = () => {
      let id;
      try { id = decodeURIComponent(location.hash.slice(1)); } catch (_) { return; }
      const target = document.getElementById(id);
      if (target && target.matches('.matrix-table tr') && target.hidden) {
        matrixForm.reset();
        query.value = ''; category.value = ''; status.value = '';
        apply();
      }
    };
    apply();
    revealHash();
    window.addEventListener('hashchange', revealHash);
  }

  const dialog = document.getElementById('search-dialog');
  const openButton = document.getElementById('search-open');
  const searchInput = document.getElementById('docs-search');
  const results = document.getElementById('search-results');
  const statusLine = document.getElementById('search-status');
  if (typeof dialog.showModal !== 'function') return;
  openButton.hidden = false;
  openButton.setAttribute('aria-label', 'Search documentation (Control or Command K)');
  let records = null;
  let loading = null;
  let active = -1;
  let links = [];
  const loadIndex = async () => {
    if (records) return records;
    if (!loading) {
      loading = fetch('/docs/assets/search-index.json', {cache: 'no-cache'}).then(response => {
        if (!response.ok) throw new Error('Search index unavailable');
        return response.json();
      }).then(data => { records = data; return data; }).catch(error => { loading = null; throw error; });
    }
    return loading;
  };
  const appendHighlight = (element, text, terms) => {
    if (!terms.length) { element.textContent = text; return; }
    const safe = terms.map(term => term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
    const expression = new RegExp(`(${safe.join('|')})`, 'gi');
    const parts = text.split(expression);
    for (let i = 0; i < parts.length; i++) {
      if (i % 2 === 0) element.append(document.createTextNode(parts[i]));
      else { const mark = document.createElement('mark'); mark.textContent = parts[i]; element.append(mark); }
    }
  };
  let searchRevision = 0;
  const render = async () => {
    const revision = ++searchRevision;
    const terms = searchInput.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
    statusLine.textContent = 'Loading the local documentation index…';
    let data;
    try { data = await loadIndex(); }
    catch (_) {
      if (revision !== searchRevision) return;
      results.replaceChildren(); links = []; active = -1;
      statusLine.textContent = 'Search could not load. Check your connection, then type again to retry. All guides remain available in the documentation menu.';
      return;
    }
    if (revision !== searchRevision) return;
    const matches = data.map(record => {
      const title = record.title.toLowerCase();
      const body = `${record.title} ${record.section} ${record.text}`.toLowerCase();
      const matchesAll = terms.every(term => body.includes(term));
      const score = terms.reduce((total, term) => {
        const escaped = term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        const exact = new RegExp(`(?:^|[^a-z0-9_])${escaped}(?=$|[^a-z0-9_])`, 'g');
        const occurrences = Math.min((body.match(exact) || []).length, 12);
        return total + (title.includes(term) ? 10 : 1) + (title.startsWith(term) ? 4 : 0) + occurrences * 2;
      }, 0);
      return {record, score, matchesAll};
    }).filter(item => item.matchesAll).sort((a, b) => b.score - a.score);
    const shown = terms.length ? matches.slice(0, 10) : data.filter(record => record.url === '/docs/quickstart/' || record.url === '/docs/support-matrix/' || record.url === '/docs/troubleshooting/').map(record => ({record}));
    results.replaceChildren();
    for (const {record} of shown) {
      const link = document.createElement('a');
      link.className = 'search-result'; link.href = record.url;
      const section = document.createElement('small'); section.textContent = record.section;
      const title = document.createElement('strong'); appendHighlight(title, record.title, terms);
      const preview = document.createElement('p');
      const first = terms.length ? record.text.toLowerCase().indexOf(terms[0]) : 0;
      const start = Math.max(0, first - 45);
      const snippet = (start ? '…' : '') + record.text.slice(start, start + 160) + (record.text.length > start + 160 ? '…' : '');
      appendHighlight(preview, snippet, terms);
      link.append(section, title, preview); results.append(link);
    }
    links = [...results.querySelectorAll('a')]; active = -1;
    statusLine.textContent = terms.length ? (matches.length ? `${matches.length} results. Showing ${shown.length}.` : 'No results. Try another keyword, annotation or framework name.') : 'Suggested starting points. Your search stays in this browser.';
  };
  const openSearch = () => {
    if (!dialog.open) dialog.showModal();
    searchInput.focus();
    render();
  };
  openButton.addEventListener('click', openSearch);
  document.getElementById('search-close').addEventListener('click', () => dialog.close());
  dialog.addEventListener('click', event => {
    if (event.target !== dialog) return;
    const bounds = dialog.getBoundingClientRect();
    if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) dialog.close();
  });
  dialog.addEventListener('close', () => { openButton.focus(); });
  document.addEventListener('keydown', event => {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault(); openSearch();
    }
  });
  searchInput.addEventListener('input', render);
  dialog.addEventListener('keydown', event => {
    if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && links.length) {
      event.preventDefault();
      const step = event.key === 'ArrowDown' ? 1 : -1;
      const focusedIndex = links.indexOf(document.activeElement);
      if (focusedIndex >= 0) active = focusedIndex;
      active = active < 0 ? (step > 0 ? 0 : links.length - 1) : (active + step + links.length) % links.length;
      links.forEach((link, i) => link.classList.toggle('is-selected', i === active));
      links[active].focus();
    } else if (event.key === 'Enter' && event.target === searchInput && links.length) {
      event.preventDefault(); links[active < 0 ? 0 : active].click();
    }
  });

  if ('IntersectionObserver' in window) {
    const tocLinks = [...document.querySelectorAll('.page-toc nav>a')];
    const observer = new IntersectionObserver(entries => {
      for (const entry of entries) if (entry.isIntersecting) {
        for (const link of tocLinks) {
          if (link.hash === '#' + entry.target.id) link.setAttribute('aria-current', 'true');
          else link.removeAttribute('aria-current');
        }
      }
    }, {rootMargin: '-10% 0px -70% 0px'});
    document.querySelectorAll('h2[id]').forEach(heading => observer.observe(heading));
  }
})();
