/* On-demand English/Turkish homepage copy. */
(() => {
// i18n
let translations = {tr:{}};
let translationsReady;
const translationFiles = ['/assets/i18n/tr.json'];
if (document.body.classList.contains('start-page')) translationFiles.push('/assets/i18n/start-tr.json');
const loadTranslations = () => translationsReady || (translationsReady = Promise.all(translationFiles.map(path => fetch(path).then(response => {
    if (!response.ok) throw new Error('Translations unavailable');
    return response.json();
}))).then(parts => {translations.tr=Object.assign({}, ...parts);}).catch(error => {translationsReady=null; throw error;}));
let currentLang = 'en';
try { currentLang = localStorage.getItem('reclazz-lang') === 'tr' ? 'tr' : 'en'; } catch (_) { /* Keep English without storage. */ }
const langToggle = document.getElementById('langToggle');
const originalTexts = {};

// Store original English texts
document.querySelectorAll('[data-i18n]').forEach(el => {
    originalTexts[el.getAttribute('data-i18n')] = el.innerHTML;
});

let languageRevision=0;
async function setLang(lang) {
    const revision=++languageRevision;
    langToggle.disabled=true;
    let translationTimeout;
    try { if(lang==='tr') await Promise.race([loadTranslations(),new Promise((_,reject)=>{translationTimeout=setTimeout(()=>reject(new Error('Translation timeout')),300);})]); }
    catch (_) { lang='en'; } finally { clearTimeout(translationTimeout); }
    if(revision!==languageRevision) return;
    langToggle.disabled=false;
    document.documentElement.classList.remove('lang-loading');
    currentLang = lang;
    try { localStorage.setItem('reclazz-lang', lang); } catch (_) { /* Keep the in-page language. */ }
    langToggle.textContent = lang === 'en' ? 'TR' : 'EN';
    langToggle.setAttribute('aria-label', lang === 'en' ? 'Switch language to Turkish' : 'İngilizceye geç');
    document.documentElement.lang = lang;
    document.querySelectorAll('[data-sap-setup]').forEach(link => {
        link.setAttribute('href', lang === 'tr' ? '/tr/sap-commerce-kurulum/' : '/docs/sap-commerce/');
        link.setAttribute('lang', lang);
    });

    document.querySelectorAll('[data-i18n]').forEach(el => {
        const key = el.getAttribute('data-i18n');
        const icon = el.querySelector('svg.icon');
        if (lang === 'tr' && translations.tr[key]) {
            // Preserve arrow spans in FAQ questions
            const arrow = el.querySelector('.faq-arrow');
            el.innerHTML = translations.tr[key].replace(/↗/g,'');
            if (icon && !el.querySelector('svg.icon')) el.appendChild(icon);
            if (arrow && !el.querySelector('.faq-arrow')) el.appendChild(arrow);
        } else if (lang === 'en' && originalTexts[key]) {
            const arrow = el.querySelector('.faq-arrow');
            el.innerHTML = originalTexts[key];
            if (arrow && !el.querySelector('.faq-arrow')) el.appendChild(arrow);
        }
    });
}

langToggle.addEventListener('click', () => {
    setLang(currentLang === 'en' ? 'tr' : 'en');
});

if (currentLang === 'tr') setLang('tr');
else document.documentElement.classList.remove('lang-loading');

})();
