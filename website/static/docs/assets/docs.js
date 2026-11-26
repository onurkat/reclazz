/* Progressive enhancement: every guide and matrix entry is present in the HTML. */
(() => {
  const root = document.documentElement;
  const local = (en, tr) => root.lang === 'tr' ? tr : en;
  const themeButton = document.getElementById('theme-toggle');
  themeButton.hidden = false;
  const describeTheme = () => {
    themeButton.setAttribute('aria-label', local(`Switch to ${root.dataset.theme === 'dark' ? 'light' : 'dark'} theme`, root.dataset.theme === 'dark' ? 'Açık temaya geç' : 'Koyu temaya geç'));
    themeButton.setAttribute('title', themeButton.getAttribute('aria-label'));
  };
  describeTheme();
  themeButton.addEventListener('click', () => {
    root.dataset.theme = root.dataset.theme === 'dark' ? 'light' : 'dark';
    try { localStorage.setItem('theme', root.dataset.theme); } catch (_) { /* Keep the in-page choice. */ }
    describeTheme();
  });

  document.querySelectorAll('.code-block pre > code').forEach(code => {
    const block = code.closest('.code-block');
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'copy-button';
    button.textContent = local('Copy', 'Kopyala');
    button.setAttribute('aria-label', local(`Copy ${block.querySelector('.code-label').textContent} example`, `${block.querySelector('.code-label').textContent} örneğini kopyala`));
    block.append(button);
    const wrap = document.createElement('button');
    wrap.type='button'; wrap.className='wrap-button'; wrap.textContent=local('Wrap', 'Satırları kaydır'); wrap.setAttribute('aria-pressed','false');
    wrap.addEventListener('click',()=>{const on=block.classList.toggle('is-wrapped');wrap.setAttribute('aria-pressed',String(on));});
    block.append(wrap);
    button.addEventListener('click', async () => {
      const text = code.textContent;
      try {
        if (!navigator.clipboard || !window.isSecureContext) throw new Error('Clipboard is unavailable');
        await navigator.clipboard.writeText(text);
        button.textContent = local('Copied', 'Kopyalandı');
        document.getElementById('copy-status').textContent = local('Example copied to clipboard.', 'Örnek panoya kopyalandı.');
      } catch (_) {
        const selection = getSelection();
        const range = document.createRange();
        range.selectNodeContents(code);
        selection.removeAllRanges();
        selection.addRange(range);
        button.textContent = local('Selected', 'Seçildi');
        document.getElementById('copy-status').textContent = local('Clipboard is unavailable. The example is selected; use your keyboard to copy.', 'Panoya erişilemiyor. Seçili örneği klavyeyle kopyalayabilirsiniz.');
      }
      setTimeout(() => { button.textContent = local('Copy', 'Kopyala'); }, 2200);
    });
  });

  const matrixForm = document.querySelector('.matrix-controls');
  if (matrixForm) {
    matrixForm.hidden = false;
    const query = document.getElementById('matrix-query');
    const category = document.getElementById('matrix-category');
    const status = document.getElementById('matrix-status');
    const rows = [...document.querySelectorAll('[data-support-id]')];
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
      const notes=document.querySelector('.matrix-notes');if(notes&&(terms.length||category.value||status.value))notes.open=rows.some(row=>row.dataset.presentation==='note'&&!row.hidden);
      count.textContent = `Showing ${visible} of ${rows.length} changes and scope notes`;
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
      if (target && target.matches('[data-support-id]') && target.hidden) {
        matrixForm.reset();
        query.value = ''; category.value = ''; status.value = '';
        apply();
      }
    };
    apply();
    revealHash();
    window.addEventListener('hashchange', revealHash);
  }

  const mobileNav = document.querySelector('.mobile-nav');
  if (mobileNav && typeof HTMLDialogElement !== 'undefined' && 'showModal' in HTMLDialogElement.prototype) {
    const drawer=document.getElementById('docs-drawer');
    const close=drawer.querySelector('.drawer-close');
    drawer.append(mobileNav.querySelector('nav').cloneNode(true));
    const open=document.querySelector('.drawer-open');
    const end=()=>drawer.close();close.addEventListener('click',end);
    open.addEventListener('click',()=>{drawer.showModal();root.classList.add('drawer-active');open.setAttribute('aria-expanded','true');close.focus();});
    drawer.addEventListener('keydown',event=>{
      if(event.key!=='Tab')return;
      const items=[...drawer.querySelectorAll('button,a[href],input,select,textarea,[tabindex="0"]')];
      const first=items[0],last=items[items.length-1];
      if(event.shiftKey&&(document.activeElement===first||document.activeElement===drawer)){event.preventDefault();last.focus();}
      else if(!event.shiftKey&&(document.activeElement===last||document.activeElement===drawer)){event.preventDefault();first.focus();}
    });
    drawer.addEventListener('close',()=>{root.classList.remove('drawer-active');open.setAttribute('aria-expanded','false');open.focus();});
    drawer.querySelectorAll('a').forEach(link=>link.addEventListener('click',end));
    drawer.addEventListener('click',event=>{if(event.target===drawer){const r=drawer.getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)end();}});
    matchMedia('(min-width:960px)').addEventListener('change',event=>{if(event.matches&&drawer.open)end();});
  }
  const revealDisclosureHash=()=>{let target;try{target=document.getElementById(decodeURIComponent(location.hash.slice(1)));}catch(_){}if(!target)return;let parent=target.parentElement;while(parent){if(parent.tagName==='DETAILS')parent.open=true;parent=parent.parentElement;}};
  revealDisclosureHash();addEventListener('hashchange',revealDisclosureHash);
  document.querySelectorAll('[role=tablist]').forEach(list=>{
    const tabs=[...list.querySelectorAll('[role=tab]')];if(!tabs.length)return;
    const activate=(tab,focus=false)=>{
      tabs.forEach(item=>{const on=item===tab;item.setAttribute('aria-selected',String(on));item.tabIndex=on?0:-1;const panel=document.getElementById(item.getAttribute('aria-controls'));if(panel)panel.hidden=!on;});
      if(focus)tab.focus();
      try{if(list.dataset.preference)localStorage.setItem('reclazz-tabs-'+list.dataset.preference,tab.dataset.choice||tab.id);}catch(_){}
    };
    tabs.forEach((tab,i)=>{
      tab.addEventListener('click',()=>{activate(tab);try{history.replaceState(null,'','#'+tab.id);}catch(_){}});
      tab.addEventListener('keydown',event=>{let next;if(event.key==='ArrowRight')next=(i+1)%tabs.length;else if(event.key==='ArrowLeft')next=(i+tabs.length-1)%tabs.length;else if(event.key==='Home')next=0;else if(event.key==='End')next=tabs.length-1;else return;event.preventDefault();activate(tabs[next],true);});
    });
    const restore=()=>{let target;try{target=document.getElementById(decodeURIComponent(location.hash.slice(1)));}catch(_){}let selected=tabs.find(t=>'#'+t.id===location.hash)||tabs.find(t=>target&&document.getElementById(t.getAttribute('aria-controls'))?.contains(target));try{if(!selected&&list.dataset.preference){const choice=localStorage.getItem('reclazz-tabs-'+list.dataset.preference);selected=tabs.find(t=>(t.dataset.choice||t.id)===choice);}}catch(_){}activate(selected||tabs[0]);if(target&&selected)requestAnimationFrame(()=>target.scrollIntoView({block:'start'}));};
    restore();addEventListener('hashchange',restore);
  });

  const dialog = document.getElementById('search-dialog');
  const openButton = document.getElementById('search-open');
  const searchInput = document.getElementById('docs-search');
  const results = document.getElementById('search-results');
  const statusLine = document.getElementById('search-status');
  if (typeof dialog.showModal !== 'function') return;
  openButton.hidden = false;
  function localizeShell() {
    describeTheme();
    openButton.setAttribute('aria-label', local('Search documentation (Control or Command K)', 'Belgelerde ara (Control veya Command K)'));
    document.getElementById('search-title').textContent = local('Search documentation', 'Belgelerde ara');
    searchInput.placeholder = local('What would you like to reload?', 'Neyi yeniden yüklemek istiyorsunuz?');
    document.getElementById('search-close').setAttribute('aria-label', local('Close search', 'Aramayı kapat'));
    statusLine.textContent = local('Search guides, changes and limits. Your query stays in this browser.', 'Rehberleri, değişiklikleri ve sınırları arayın. Sorgunuz bu tarayıcıda kalır.');
    document.querySelector('.search-hint').textContent = local('↑ ↓ to select · Enter to open · Esc to close', '↑ ↓ ile seçin · Enter ile açın · Esc ile kapatın');
    const labels = [
      ['.brand', 'Reclazz home', 'Reclazz ana sayfası'],
      ['.header-links', 'Main navigation', 'Ana menü'],
      ['.site-menu summary', 'Site navigation', 'Site menüsü'],
      ['.site-menu nav', 'Mobile main navigation', 'Mobil ana menü'],
      ['.site-footer nav:nth-of-type(1)', 'Product links', 'Ürün bağlantıları'],
      ['.site-footer nav:nth-of-type(2)', 'Project links', 'Proje bağlantıları'],
      ['.site-footer nav:nth-of-type(3)', 'Legal links', 'Yasal bilgiler'],
    ];
    for (const [selector, en, tr] of labels) {
      document.querySelectorAll(selector).forEach(el => el.setAttribute('aria-label', local(en, tr)));
    }
  }
  localizeShell();
  new MutationObserver(localizeShell).observe(root, {attributes:true, attributeFilter:['lang']});
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
    statusLine.textContent = local('Loading the local documentation index…', 'Belge dizini yükleniyor…');
    let data;
    try { data = await loadIndex(); }
    catch (_) {
      if (revision !== searchRevision) return;
      results.replaceChildren(); links = []; active = -1;
      statusLine.textContent = local('Search could not load. Check your connection, then type again to retry. All guides remain available in the documentation menu.', 'Arama yüklenemedi. Bağlantınızı kontrol edip yeniden yazın. Tüm rehberlere belge menüsünden ulaşabilirsiniz.');
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
    const groups=new Map();
    shown.forEach(item=>{const key=item.record.url.split('#')[0];if(!groups.has(key))groups.set(key,[]);groups.get(key).push(item);});
    let previousGroup='';
    for (const {record} of [...groups.values()].flat()) {
      const group=record.url.split('#')[0];
      if(group!==previousGroup){const heading=document.createElement('div'); heading.className='search-group';heading.textContent=record.section;results.append(heading);previousGroup=group;}
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
    statusLine.textContent = terms.length ? (matches.length ? local(`${matches.length} results. Showing ${shown.length}.`, `${matches.length} sonuç bulundu. ${shown.length} sonuç gösteriliyor.`) : local('No results. Try another keyword, annotation or framework name.', 'Sonuç bulunamadı. Başka bir sözcük, anotasyon veya framework adı deneyin.')) : local('Suggested starting points. Your search stays in this browser.', 'Başlangıç için öneriler. Sorgunuz bu tarayıcıda kalır.');
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
