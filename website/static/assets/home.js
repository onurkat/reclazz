/* Homepage FAQ, public star count and retained English/Turkish copy. */
(() => {
// FAQ accordion
document.querySelectorAll('.faq-question').forEach(btn => {
    btn.addEventListener('click', () => {
        const item = btn.parentElement;
        document.querySelectorAll('.faq-item').forEach(other => {
            if (other !== item) {
                other.classList.remove('open');
                other.querySelector('.faq-question').setAttribute('aria-expanded', 'false');
            }
        });
        item.classList.toggle('open');
        btn.setAttribute('aria-expanded', item.classList.contains('open') ? 'true' : 'false');
    });
});


document.documentElement.classList.add('home-interactive');

// GitHub stars
fetch('https://api.github.com/repos/onurkat/reclazz')
    .then(r => r.ok ? r.json() : null)
    .then(data => {
        if (data && data.stargazers_count !== undefined) {
            const count = data.stargazers_count;
            const starEl = document.getElementById('starCount');
            const proofStars = document.getElementById('proofStars');
            const proof = document.getElementById('socialProof');
            if (starEl) {
                starEl.textContent = count.toLocaleString();
                starEl.style.display = 'inline';
            }
            if (proofStars && count > 0) {
                proofStars.textContent = count.toLocaleString();
                if (proof) proof.style.display = 'flex';
            }
        }
    })
    .catch(() => {});

// i18n
const translations = {
    tr: {
        "site.start": "Başla ↗",
        "site.tagline": "Bir sonraki değişikliğe odaklanın.",
        "site.product": "Ürün",
        "site.documentation": "Dokümantasyon",
        "site.quickstart": "Hızlı başlangıç",
        "site.support": "Reload kapsamı",
        "site.compare": "Karşılaştır",
        "site.project": "Proje",
        "site.releases": "Sürümler ↗",
        "site.issues": "Sorun bildir ↗",
        "site.legal": "Yasal",
        "site.license": "Lisans",
        "site.privacy": "Gizlilik",
        "site.sponsor": "Geliştirmeyi destekle ↗",

        'nav.features': '\u00D6zellikler',
        'nav.compare': 'Kar\u015F\u0131la\u015Ft\u0131r',
        'nav.jdk': 'JDK Deste\u011Fi',
        'nav.howitworks': 'Nas\u0131l \u00C7al\u0131\u015F\u0131r',
        'nav.install': 'Kurulum',
        'nav.docs': 'Dokümantasyon',
        'hero.badge': '\u00DCcretsiz & A\u00E7\u0131k Kaynak',
        'hero.title': '<span class="text-gradient">SAP Commerce\'i</span><br>Yeniden Ba\u015Flatmay\u0131 B\u0131rak\u0131n',
        'hero.subtitle': "Düzenleyin. Derleyin. JVM çalışmaya devam etsin. Desteklenen Java ve Spring değişikliklerini, SAP Commerce akışlarını ve yeniden başlatma sınırlarını keşfedin.",
        'hero.install': 'Marketplace\'den Y\u00FCkle',
        'hero.github': 'GitHub\'da Y\u0131ld\u0131zla',
        'features.label': '\u00D6zellikler',
        'features.title_html': 'Derin Spring Entegrasyonu.<br><span class="text-gradient">Sadece Hot-Swap De\u011Fil.</span>',
        'features.subtitle': "Reclazz, Spring iç yapısını anlar. Bir sınıf yenilendiğinde bean'ler tazelenir, mapping'ler yeniden taranır, önbellekler boşaltılır ve zamanlayıcılar yeniden kaydolur; açılıştan sonra eklediğiniz bir metot da onu arayacak framework tarafından bulunur.",
        'features.bean.title': 'Spring Bean Yenileme',
        'features.bean.desc': 'S\u0131n\u0131f yenilendikten sonra singleton bean\'leri otomatik olarak yok edip yeniden olu\u015Fturur. Uygulama durumu korunur.',
        'features.mvc.title': 'MVC Yeniden Tarama',
        'features.mvc.desc': "Bir controller değiştiğinde @RequestMapping metotları yeniden kaydolur. Eklediğiniz bir endpoint, sıradan bir JDK'da ve bir Tomcat webapp'i içinde de, restart sonrası yerine bir sonraki istekte cevap verir. Çalışan bir controller'a ya da advice'a eklediğiniz @ExceptionHandler, @InitBinder veya @ModelAttribute, yerel öncelik ve advice sıralaması dahil Spring'in kendi seçimiyle çalışır.",
        'features.cache.title': '\u00D6nbellek Temizleme',
        'features.cache.desc': '@Cacheable, @CacheEvict veya @CachePut i\u00E7eren s\u0131n\u0131flar i\u00E7in Spring \u00F6nbelleklerini temizler. Yenileme sonras\u0131 eski veri kalmaz.',
        'features.scheduler.title': 'Zamanlay\u0131c\u0131 Yenileme',
        'features.scheduler.desc': "@Scheduled görevlerini iptal edip yeniden kaydeder. Değişen cron ifadeleri ve sabit aralıklı zamanlamalar hemen uygulanır; açılıştan sonra eklediğiniz bir @Scheduled metodu standart JDK'da kaydolur, metodu ya da anotasyonunu kaldırmak görevi iptal eder.",
        'features.structural.title': 'Yap\u0131sal De\u011Fi\u015Fiklikler',
        'features.structural.desc': "Her JDK 17+ s\u00fcr\u00fcm\u00fcnde metot ve \u00f6rnek alan\u0131 ekleyin, kald\u0131r\u0131n, anotasyon de\u011fi\u015ftirin. Yamal\u0131 bir JVM gerekmez. Eklenen statik alan ba\u015flang\u0131\u00e7 de\u011ferini de al\u0131r, statik blo\u011fun geri kalan\u0131 yeniden \u00e7al\u0131\u015fmadan. Sona eklenen bir enum de\u011feri \u00e7al\u0131\u015fan JVM'e uygulan\u0131r, JDK 26 dahil: o s\u00fcr\u00fcm\u00fcn kapatt\u0131\u011f\u0131 \u015fey sun.misc.Unsafe ve JDK'n\u0131n kendi Unsafe'i ayn\u0131 yazmay\u0131 yap\u0131yor. De\u011fi\u015fen bir derleme zaman\u0131 sabiti, onu inline eden dosyalarla birlikte adland\u0131r\u0131l\u0131r ve derleyici Reclazz ise yeniden derlenir.",
        'features.templates.title': '\u015Eablon Yeniden Y\u00FCkleme',
        'features.templates.desc': 'Thymeleaf veya Freemarker \u015Fablonunu d\u00FCzenledi\u011Finizde bir sonraki render\u0027da ge\u00E7erli olur; \u00F6nbellek a\u00E7\u0131k kalabilir.',
        'features.props.title': 'Property ve Log De\u011Fi\u015Fiklikleri',
        'features.props.desc': "Spring Boot bir property dosyasını yalnız açılışta okur. Bir .properties ya da .yml dosyasını şimdi kaydedin: dosya Environment'taki kendi yerinde yeniden yüklenir, böylece komut satırı override'ları kazanmaya devam eder, iç içe bir YAML değeri ya da liste açılıştaki aynı bağlamaya ulaşır ve sildiğiniz bir anahtar bayat kalmak yerine bir sonraki kaynağa ya da varsayılana düşer. Dosyayı okuyan @ConfigurationProperties bean'leri yeniden bağlanır; constructor ile bağlananlar yeniden kurulup referansları yeni örneğe çevrilir; @Value alanları, hesaplanan ifadeler dahil, yeniden çözülür. Log seviyeleri ile kaydedilen bir logback.xml veya log4j2.xml de çalışan log altyapısına ulaşır. Geçersiz bir değer kaydın tamamını bekletir ve nedenini söyler.",
        'features.diag.title': 'Kendini A\u00E7\u0131kl\u0131yor',
        'features.diag.desc': 'Hi\u00E7bir \u015Fey olmad\u0131\u011F\u0131nda nedenini sorun: build o s\u0131n\u0131fa u\u011Framam\u0131\u015Ft\u0131r, s\u0131n\u0131f Reclazz\'\u0131n izlemedi\u011Fi bir yere derleniyordur, \u00FCretilen byte\'lar ayn\u0131 \u00E7\u0131km\u0131\u015Ft\u0131r ya da JVM s\u0131n\u0131f\u0131 hen\u00FCz y\u00FCklememi\u015Ftir. Bunlar\u0131n hi\u00E7biri hata \u00FCretmez, log\'un bo\u015F olmas\u0131n\u0131n sebebi budur. \u0130kinci soru da \u015Fu: neyin i\u00E7in h\u00E2l\u00E2 restart gerekiyor. Bu oturumun geride b\u0131rakt\u0131klar\u0131n\u0131 listeler, hi\u00E7bir \u015Fey yoksa onu da a\u00E7\u0131k\u00E7a s\u00F6yler.',
        'features.aop.title': 'AOP & Olaylar',
        'features.aop.desc': "@Aspect sınıfları için AOP proxy önbelleklerini temizler ve düzenlenen bir pointcut'ı yeniden ayrıştırır; her proxy'yi eski ifadeyle eşleşir halde bırakmaz. Hâlâ restart gerektiren yarı adlandırılır: zaten proxy'lenmiş bir bean, kendisi yenilenene kadar kurulduğu advice'ı korur. @EventListener metotlarını, açılıştan sonra eklediğiniz dahil, yeniden kaydeder; eklediğiniz bir @TransactionalEventListener Spring'in kendi senkronizasyonu üzerinden kendi işlem fazında tetiklenir. @Async işlemesini tazeler.",
        'features.data.title': 'Spring Data & G\u00FCvenlik',
        'features.data.desc': "Repository bean'lerini yeniler. D\u00fczenlenen bir @EnableWebSecurity yap\u0131land\u0131rmas\u0131 \u00e7al\u0131\u015fan filter chain'e uygulan\u0131r; method security (@PreAuthorize, @Secured) ise yaln\u0131z g\u00fcvenlik yap\u0131land\u0131rmas\u0131nda de\u011fil, onu ta\u015f\u0131yan servis s\u0131n\u0131f\u0131nda yeniden okunur. Çalışan bir servis metoduna eklediğiniz @PreAuthorize veya @PostAuthorize, doğrudan ya da sabit bir bileşik işaret olarak, hem klasik Spring 5 hem de modern Spring 6 yığınında uygulanır.",
        'features.caches.title': "Framework'lerin Cache'ledi\u011fi",
        'features.caches.desc': "Yeniden yükleme işin yalnız yarısı. Her framework, sınıfınız hakkında bir kez, açılışta hesapladığı cevabı saklar ve kimse ona değiştiğini söylemez. Artık mevcut bir alana eklediğiniz @Autowired gerçekten enjekte ediliyor, eklediğiniz bir kısıt bir sonraki istekte uygulanıyor, eklediğiniz @ExceptionHandler, @InitBinder veya @ModelAttribute çalışıyor, düzenlediğiniz @Aspect pointcut'ı yeniden ayrıştırılıyor ve Jackson ilk gördüğü şekli döndürmeyi bırakıyor: bir DTO'ya eklediğiniz getter, setter ya da alan bir sonraki isteğin JSON'ında yazılıp okunuyor. Her biri çalışan bir sunucuda, öncesi ve sonrası ölçüldü.",
        'features.hotswap.title': 'Anl\u0131k Hot-Swap',
        'features.hotswap.desc': '\u00C7al\u0131\u015Fan JVM\'deki s\u0131n\u0131flar\u0131 Instrumentation API ile yeniden tan\u0131mlar. ~50ms\'de uygulan\u0131r. S\u0131f\u0131r kesinti.',
        'features.privacy.title': '100% Yerel & Gizli',
        'features.privacy.desc': 'Telemetri yok, analitik yok, lisans sunucusu yok, d\u0131\u015F istek yok. Her \u015Fey makinenizde \u00E7al\u0131\u015F\u0131r. Kodunuz ortam\u0131n\u0131zdan asla \u00E7\u0131kmaz. \u0130ndirdi\u011Finiz paket lisans metinlerini ve ajan\u0131n i\u00E7indeki her \u015Feyin listesini de ta\u015F\u0131r; sizin ad\u0131n\u0131za inceleyecek ki\u015Fi i\u00E7in.',
        'compare.label': 'Kar\u015F\u0131la\u015Ft\u0131rma',
        'compare.title_html': '<span class="text-gradient">Reclazz</span> vs DevTools vs JRebel',
        'compare.subtitle': 'Spring Boot DevTools t\u00FCm uygulama ba\u011Flam\u0131n\u0131 yeniden ba\u015Flat\u0131r. JRebel y\u0131lda $550+ tutar. Reclazz \u00FCcretsiz, a\u00E7\u0131k kaynak ve ger\u00E7ek yerinde hot-swap yapar.',
        'compare.feature': '\u00D6zellik',
        'compare.price': 'Fiyat',
        'compare.reload_type': 'Yenileme t\u00FCr\u00FC',
        'compare.app_state': 'Uygulama durumu',
        'compare.speed': 'Yenileme h\u0131z\u0131',
        'compare.method_body': 'Metot g\u00F6vdesi de\u011Fi\u015Fiklikleri',
        'compare.structural': 'Metot ve alan ekleme/kald\u0131rma',
        'compare.footnote_html': 'Reclazz s\u00FCtunu, <a href="https://github.com/onurkat/reclazz" target="_blank" rel="noopener">test paketinin</a> SAP Commerce 2211 ile Spring Boot 2.7 ve 3.3 \u00FCzerinde kapsad\u0131\u011F\u0131d\u0131r. JRebel s\u00FCtunu ise JRebel\'in kendi belgelerinde anlat\u0131lanlard\u0131r; belgede ge\u00E7meyen yerde tahmin y\u00FCr\u00FCtmek yerine bunu yaz\u0131yoruz. JRebel Java s\u0131n\u0131flar\u0131n\u0131 iyi yeniden y\u00FCkl\u00FCyor; bo\u015F bir h\u00FCcre k\u0131lavuzundaki bir bo\u015Fluktur, ba\u015Far\u0131s\u0131z oldu\u011Fu iddias\u0131 de\u011Fil.',
        'compare.jpa': 'JPA e\u015Flemesi yeni alan\u0131 al\u0131r',
        'compare.enum': 'Sona enum de\u011Feri ekleme',
        'compare.interfaces': 'Aray\u00FCz ekleme / kald\u0131rma',
        'compare.superclass': '\u00DCst s\u0131n\u0131f de\u011Fi\u015Ftirme',
        'compare.static_fields': 'Eklenen statik alan de\u011Ferini al\u0131r',
        'compare.spring_bean': 'Spring bean yenileme',
        'compare.new_bean': 'Yeni @Service / @Controller s\u0131n\u0131f\u0131',
        'compare.security': 'Spring Security kurallar\u0131',
        'compare.removed': 'Sildi\u011finiz \u00fcyeler g\u00f6r\u00fcnmez olur',
        'compare.new_entity': 'Yeni @Entity s\u0131n\u0131f\u0131 e\u015Flenir',
        'compare.mvc': 'MVC e\u015Fleme yeniden tarama',
        'compare.cache': '\u00D6nbellek temizleme',
        'compare.scheduler': '@Scheduled yeniden kay\u0131t',
        'compare.events': '@EventListener yenileme',
        'compare.aop': 'AOP proxy yenileme',
        'compare.sap': 'SAP Commerce deste\u011Fi',
        'compare.open_source': 'A\u00E7\u0131k kaynak',
        'sap.label': 'SAP Commerce',
        'sap.title_html': 'SAP Commerce\'e <span class="text-gradient">Uyarlanmad\u0131, Onun \u0130\u00E7in Yaz\u0131ld\u0131.</span>',
        'sap.subtitle': 'Java s\u0131n\u0131flar\u0131n\u0131 yeniden y\u00FCklemek i\u015Fin kolay yar\u0131s\u0131. SAP Commerce\'te bir g\u00FCn\u00FCn de\u011Fi\u015Fikliklerinin \u00E7o\u011Fu Java bile de\u011Fil: items.xml\'de bir tip, bir property, bir locales dosyas\u0131ndaki bir sat\u0131r metin, bir ImpEx. Reclazz bunlar\u0131 da yeniden y\u00FCkl\u00FCyor. A\u015Fa\u011F\u0131daki maddelerin hepsi \u00E7al\u0131\u015Fan bir 2211 sunucusunda \u00F6l\u00E7\u00FCld\u00FC.',
        'sap.items.title': 'items.xml ve beans.xml',
        'sap.items.desc': 'Kaydetti\u011Finizde platformun kendi kod \u00FCretimini \u00E7al\u0131\u015Ft\u0131r\u0131p yeniden \u00FCretilen model ve DTO s\u0131n\u0131flar\u0131n\u0131 y\u00FCkler, ant clean all ve restart yerine. Yeni bir attribute h\u00E2l\u00E2 veritaban\u0131 kolonu ister, o y\u00FCzden Reclazz \u015Feman\u0131za dokunmak yerine Update Running System \u00E7al\u0131\u015Ft\u0131rman\u0131z\u0131 s\u00F6yler.',
        'sap.spring.title': 'Spring XML',
        'sap.spring.desc': "Bir *-spring.xml'deki property düzenlemeleri canlı bean'e yerinde uygulanır. Değişen bir constructor argümanı, factory metodu ya da init/destroy metodu o singleton'ı Spring'in kendisi üzerinden yeniden yaratır ve eski örneği hâlâ tutan bean'leri onarır; yepyeni bir bean tanımı da restart olmadan kullanılabilir hale gelir.",
        'sap.props.title': 'Property\'ler',
        'sap.logging.title': 'Log Seviyeleri',
        'sap.logging.desc': 'SAP Commerce log seviyelerini bir log4j2.xml\'de de\u011Fil, property dosyalar\u0131nda ad ve seviye \u00E7ifti olarak tutar. Birini de\u011Fi\u015Ftirdi\u011Finizde \u00E7al\u0131\u015Fan logger\'a ula\u015F\u0131r; bir s\u0131n\u0131f\u0131 debug\'a \u00E7ekmek restart de\u011Fil, bir kaydetme kadar s\u00FCrer. Dokunmad\u0131\u011F\u0131n\u0131z seviyeler yerinde kal\u0131r, HAC konsolundan y\u00FCkseltti\u011Finiz bir seviye dahil.',
        'sap.props.desc': "Platform property dosyalarını yalnız açılışta okur. Artık kaydettiğinizde değeri gerçekten değişen anahtarlar, HAC konsolunun yaptığı aynı çağrıyla çalışan sunucuya uygulanır. Yalnız açılışta okunan değerler \"uygulandı\" diye raporlanmak yerine hâlâ restart gerektirdiği söylenerek adlandırılır; platformun yazmayı reddettiği bir anahtar bekleyen durumda kalır, aynı dosyayı yeniden kaydetmek onu tekrar dener.",
        'sap.l10n.title': 'Tip ve Enum Metinleri',
        'sap.l10n.desc': 'Bir locales dosyas\u0131n\u0131 d\u00FCzenleyin, yeni metin bir sonraki okumada gelir. System update yok, veritaban\u0131 yaz\u0131m\u0131 yok, restart yok.',
        'sap.labels.title': 'Backoffice Label\'lar\u0131',
        'sap.labels.desc': 'Label dosyalar\u0131 backoffice\'in ger\u00E7ekten okudu\u011Fu \u00F6nbelle\u011Fe ula\u015F\u0131r, hem de her kopyas\u0131na. G\u00F6r\u00FCn\u00FCm\u00FC yeniden a\u00E7\u0131n, metniniz orada.',
        'sap.interceptors.title': 'Interceptor\'lar',
        'sap.interceptors.desc': "Validate, Prepare, Load, Remove ve InitDefaults interceptor'ları yeniden kaydolur: mapping Spring bean'i yenilemeden önce yakalanır, sonrasında platform registry'sine geri konur ve yeni kuralı tam bir kez çalıştıran gerçek bir model kaydıyla doğrulanır. Başarısız bir kayıt başarı iddia etmek yerine bunu söyler.",
        'sap.impex.title': 'Kaydedince ImpEx',
        'sap.impex.desc': "Değişen .impex dosyaları kaydettiğiniz anda içeri alınabilir. Canlı veritabanınıza karşı çalıştığı için opt-in'dir; açıkça yazılmış ya da bir makronun arkasına saklanmış REMOVE başlığı taşıyan bir dosya çalıştırılmak yerine adlandırılıp reddedilir. Koruma katmanının çözemediği bir başlık makrosu da aynı şekilde reddedilir.",
        'sap.ext.title': 'Eklentileri Tan\u0131r',
        'sap.ext.desc': 'Eklenti yap\u0131n\u0131z\u0131, classpath\'i ve classloader hiyerar\u015Fisini kendi okur. Yaz\u0131p g\u00FCncel tutman\u0131z gereken eklenti ba\u015F\u0131na bir tan\u0131m dosyas\u0131 yoktur.',
        'sapcmp.label': '\u00D6zellikle SAP Commerce\'te',
        'sapcmp.title_html': 'Hybris\'te <span class="text-gradient">Reclazz</span> ve JRebel',
        'sapcmp.subtitle': 'JRebel SAP Commerce\'te Java s\u0131n\u0131flar\u0131n\u0131 yeniden y\u00FCkl\u00FCyor ve bunu iyi yap\u0131yor. Bir hybris g\u00FCn\u00FCn\u00FCn geri kalan\u0131 ayr\u0131 bir soru; yan\u0131tlamak i\u00E7in en d\u00FCr\u00FCst yer de JRebel\'in kendi SAP Commerce k\u0131lavuzu.',
        'sapcmp.what': 'De\u011Fi\u015Ftirdi\u011Finiz \u015Fey',
        'sapcmp.price': 'Fiyat',
        'sapcmp.classes': 'Bir Java s\u0131n\u0131f\u0131',
        'sapcmp.setup': 'Eklenti ba\u015F\u0131na kurulum dosyas\u0131',
        'sapcmp.items': 'items.xml veya beans.xml\'de bir tip',
        'sapcmp.logging': 'Bir log seviyesi',
        'sapcmp.props': 'Bir property',
        'sapcmp.l10n': 'Locales dosyas\u0131nda bir tip veya enum ad\u0131',
        'sapcmp.labels': 'Bir backoffice label\'\u0131',
        'sapcmp.interceptors': 'Bir interceptor',
        'sapcmp.impex': 'Bir ImpEx dosyas\u0131',
        'sapcmp.offline': 'Lisans sunucusu ve aktivasyon',
        'sapcmp.footnote_html': 'Kar\u015F\u0131la\u015Ft\u0131rma <a href="https://manuals.jrebel.com/jrebel/advanced/hybris.html" target="_blank" rel="noopener">JRebel\'in kendi SAP Commerce k\u0131lavuzuna</a> g\u00F6re yap\u0131ld\u0131; o k\u0131lavuz derlenmi\u015F s\u0131n\u0131flar\u0131n yeniden y\u00FCklenmesini ve rebel.xml yap\u0131land\u0131rmas\u0131n\u0131 anlat\u0131yor. \u00C7arp\u0131 i\u015Fareti, o yetene\u011Fin orada tarif edilmedi\u011Fi anlam\u0131na gelir, JRebel bozuk demek de\u011Fildir: Hybris\'te s\u0131n\u0131flar\u0131 yeniden y\u00FCkl\u00FCyor ve belgeledi\u011Fi \u015Fey bu.',
        'nav.sap': 'SAP Commerce',
        'sapcmp.v.free': '\u00DCcretsiz ve a\u00E7\u0131k kaynak',
        'sapcmp.v.price_jr': 'Kullan\u0131c\u0131 ba\u015F\u0131na 550+ dolar/y\u0131l',
        'sapcmp.v.none': 'Yok',
        'sapcmp.v.rebelxml': 'backoffice eklentileri i\u00E7in rebel.xml',
        'sapcmp.v.regen': 'yeniden \u00FCretir ve y\u00FCkler',
        'sapcmp.v.applied': '\u00E7al\u0131\u015Fan sunucuya uygulan\u0131r',
        'sapcmp.v.rereg': 'yeniden kaydolur',
        'sapcmp.v.optin': 'iste\u011Fe ba\u011Fl\u0131, kaydedince',
        'sapcmp.v.notdoc': 'Hybris k\u0131lavuzunda yok',
        'sapcmp.v.nolic': 'Yok, makinenizden hi\u00E7bir \u015Fey \u00E7\u0131kmaz',
        'sapcmp.v.commercial': 'Ticari lisans, aktivasyon gerekir',
        'jdk.label': 'JDK Deste\u011Fi',
        'jdk.title_html': '<span class="text-gradient">JDK\'n\u0131zla</span> \u00C7al\u0131\u015F\u0131r',
        'jdk.verified': 'Varsay\u0131m de\u011Fil, \u00F6l\u00E7\u00FCm: SAP Commerce 2211 (\u00E7al\u0131\u015Fan sunucuda 20 senaryonun 20\'si), Spring Boot 2.7 ve 3.3, Java 17 ve 21 ile JetBrains Runtime 25, IntelliJ IDEA 2023.3 ile 2026.2 aras\u0131.',
        'jdk.subtitle': 'Reclazz JDK\'n\u0131z\u0131 otomatik alg\u0131lar ve do\u011Fru bayraklar\u0131 yap\u0131land\u0131r\u0131r. Yap\u0131sal de\u011Fi\u015Fiklikler desteklenen JDK yollar\u0131nda \u00E7al\u0131\u015F\u0131r; JetBrains Runtime ek olarak yeni \u00FCyeleri reflection\'a g\u00F6r\u00FCn\u00FCr k\u0131lar.',
        'jdk.standard.title': 'Standart JDK',
        'jdk.standard.structural': 'Metot ve alan ekleme/kald\u0131rma',
        'jdk.standard.method': 'Metot g\u00F6vdesi de\u011Fi\u015Fiklikleri',
        'jdk.standard.spring': 'Spring bean yenileme',
        'jdk.standard.mvc': 'MVC yeniden tarama & \u00F6nbellek temizleme',
        'jdk.standard.scheduler': 'Zamanlay\u0131c\u0131 & olay yenileme',
        'jdk.jbr.title': 'JetBrains Runtime / DCEVM',
        'jdk.jbr.everything': 'Yukar\u0131dakilerin hepsi, art\u0131:',
        'jdk.jbr.reflection': 'Yeni \u00FCyeler reflection ile g\u00F6r\u00FCn\u00FCr',
        'jdk.jbr.caches': 'Reflection \u00F6nbellekleri yeniden ba\u015Flatmadan g\u00F6r\u00FCr',
        
        'jdk.standard.annotations': 'Anotasyon de\u011Fi\u015Ftirme',
        'architecture.label': 'Mimari',
        'architecture.title_html': '<span class="text-gradient">Reclazz</span> Nas\u0131l \u00C7al\u0131\u015F\u0131r',
        'architecture.subtitle': 'Uygulama JVM\'nizin i\u00E7inde hafif bir Java agent \u00E7al\u0131\u015F\u0131r. Derlenen s\u0131n\u0131flar\u0131 izler ve Spring-bilin\u00E7li yenilemeyi koordine eder.',
        'limits.label': '\u015Eeffafl\u0131k',
        'limits.title_html': 'Hot-Reload <span class="text-gradient">Yap\u0131lamayan</span> \u015Eeyler',
        'limits.subtitle': 'Bunlar hi\u00E7bir arac\u0131n tam olarak a\u015Famayaca\u011F\u0131 temel JVM ve framework s\u0131n\u0131rlamalar\u0131d\u0131r.',
        'limits.hierarchy_title': 'S\u0131n\u0131f Hiyerar\u015Fisi De\u011Fi\u015Fiklikleri',
        'limits.hierarchy_desc': '\u00DCst s\u0131n\u0131f veya aray\u00FCz de\u011Fi\u015Ftirmek JVM s\u0131n\u0131rlamas\u0131d\u0131r. Hi\u00E7bir ara\u00E7 bunu a\u015Famaz. Yeniden ba\u015Flatma gerekir.',
        'limits.structural_title': 'Yeni \u00DCyelerde Reflection (standart JDK)',
        'limits.structural_desc': "Metot ve alan ekleme/kaldırma desteklenen JDK 17+ yollarında çalışır. Standart bir JDK'da yeni üyeler gizli bir companion sınıfında yaşar; onları doğrudan çağıran kod görür ama orijinal sınıf üzerindeki reflection görmez. Spring MVC, @Bean fabrikaları, zamanlayıcılar, olay ve işlem dinleyicileri, yaşam döngüsü callback'leri, Jackson ile Kafka, JMS ve RabbitMQ dinleyici container'ları kaydedilen metadata'yı adaptörler üzerinden alır; adaptörü olmayan bir framework, örneğin Hybris ModelService nitelik dağıtımı, hâlâ restart gerektirir ve adıyla bildirilir. JetBrains Runtime veya DCEVM bu kısıtı tamamen kaldırır.",
        'limits.config_title': "Dosya Düzenlemesinin Ötesindeki Yapılandırma",
        'limits.config_desc': "Yüklenmiş bir properties ya da YAML dosyası kendi önceliğinde yeniden yüklenir, desteklenen XML düzenlemeleri bean'i yeniden yaratır. Hâlâ restart gerektirenler: yeni bir config konumu, profil ya da import, XML'den kaldırılan bir bean tanımı, değişen sınıf, scope ya da parent, iç içe bean veya yönetilen koleksiyon içeren XML tanımları. Her biri olduğu anda adlandırılır.",
        'limits.new_beans_title': "Creator'lar, Proxy'ler ve Özel Taramalar",
        'limits.new_beans_desc': "Yeni bir @Component sınıfı canlıdır, ama bazı şekiller dışarıda kalır: eklenen property'ler için Jackson constructor ve record creator'ları, proxy'lenmiş bir bean'e eklenen dinleyici ya da yaşam döngüsü callback'i, özel dinleyici fabrikaları, Jakarta yaşam döngüsü anotasyonları ve sınıflarınızı kendi reflection'ıyla okuyan framework'ler. Reclazz bunları yarısını uygulamak yerine nedeniyle birlikte reddeder.",
        'install.label': 'Ba\u015Flayal\u0131m',
        'install.title_html': '<span class="text-gradient">3 Ad\u0131mda</span> \u00C7al\u0131\u015F\u0131r Duruma Gelin',
        'install.step1.title': 'Agent\'\u0131 Ekleyin',
        'install.step1.desc': 'Agent\u0027\u0131 en son GitHub s\u00FCr\u00FCm\u00FCnden indirin ve JVM arg\u00FCmanlar\u0131n\u0131za -javaagent bayra\u011F\u0131n\u0131 ekleyin; ya da sizin yerinize enjekte eden IntelliJ eklentisini kurun.',
        'install.step2.title': 'Uygulaman\u0131z\u0131 \u00C7al\u0131\u015Ft\u0131r\u0131n',
        'install.step2.desc': 'Spring Boot uygulaman\u0131z\u0131 normal \u015Fekilde ba\u015Flat\u0131n. Reclazz proje d\u00FCzeninizi otomatik alg\u0131lar ve izlemeye ba\u015Flar.',
        'install.step3.title': 'Derle & Yenile',
        'install.step3.desc': 'Kodunuzu de\u011Fi\u015Ftirin, sonra derleyin. Reclazz de\u011Fi\u015Fiklikleri otomatik olarak hot-swap yapar. Yeniden ba\u015Flatma gerekmez.',
        'faq.title_html': 'S\u0131k Sorulan <span class="text-gradient">Sorular</span>',
        'faq.q1': 'Reclazz \u00E7al\u0131\u015Fan uygulamamam\u0131 bozar m\u0131?',
        'faq.a1': 'Hay\u0131r. Reclazz standart JVM Instrumentation API kullan\u0131r, hata ay\u0131klay\u0131c\u0131lar ve profiler\'lar\u0131n kulland\u0131\u011F\u0131 ayn\u0131 mekanizma. Hot-swap ba\u015Far\u0131s\u0131z olursa, orijinal s\u0131n\u0131f y\u00FCkl\u00FC kal\u0131r ve uygulaman\u0131z normal \u00E7al\u0131\u015Fmaya devam eder.',
        'faq.q2': 'Reclazz Spring Boot DevTools\'dan nas\u0131l farkl\u0131?',
        'faq.a2': 'DevTools her de\u011Fi\u015Fiklikte t\u00FCm Spring uygulama ba\u011Flam\u0131n\u0131 yeniden ba\u015Flat\u0131r, bu saniyeler s\u00FCrer ve t\u00FCm uygulama durumunu kaybeder. Reclazz ger\u00E7ek yerinde s\u0131n\u0131f yeniden tan\u0131mlama yapar, durumunuz korunur ve yenilemeler ~50ms\'de tamamlan\u0131r. Reclazz ayr\u0131ca MVC yeniden tarama, \u00F6nbellek temizleme ve zamanlay\u0131c\u0131 yeniden kay\u0131t gibi Spring\'e \u00F6zel konular\u0131 yeniden ba\u015Flatma olmadan ele al\u0131r.',
        'faq.q3': 'Reclazz Maven ve Gradle ile \u00E7al\u0131\u015F\u0131r m\u0131?',
        'faq.a3': 'Evet. Reclazz classpath\'ten <code style="font-family:var(--font-mono);font-size:0.9em">target/classes</code> (Maven) veya <code style="font-family:var(--font-mono);font-size:0.9em">build/classes/java/main</code> (Gradle) dizinini otomatik alg\u0131lar. <code style="font-family:var(--font-mono);font-size:0.9em">mvn compile</code> veya <code style="font-family:var(--font-mono);font-size:0.9em">gradle classes</code> \u00E7al\u0131\u015Ft\u0131r\u0131n, Reclazz de\u011Fi\u015Fiklikleri otomatik hot-swap yapar.',
        'faq.q4': 'Hot-swap ba\u015Far\u0131s\u0131z olursa ne olur?',
        'faq.a4': 'Reclazz hatay\u0131 ara\u00E7 penceresinde ve bildirimle raporlar. Orijinal s\u0131n\u0131f y\u00FCkl\u00FC kal\u0131r, uygulaman\u0131z asla bozuk durumda b\u0131rak\u0131lmaz. Yayg\u0131n neden: \u00FCst s\u0131n\u0131f veya aray\u00FCz de\u011Fi\u015Fiklikleri, ki bunlar her zaman yeniden ba\u015Flatma gerektirir.',
        'faq.q5': 'Reclazz harici sunuculara veri g\u00F6nderir mi?',
        'faq.a5': 'Hay\u0131r. Reclazz %100 yereldir. Telemetri, analitik, lisans sunucusu veya d\u0131\u015F a\u011F iste\u011Fi yoktur. Her \u015Fey tamamen makinenizde \u00E7al\u0131\u015F\u0131r.',
        'faq.q6': 'Reclazz SAP Commerce (Hybris) destekliyor mu?',
        'faq.a6': 'Evet, zaten Reclazz\'in var olma sebebi bu. Bir <strong>*-items.xml</strong> ya da <strong>*-beans.xml</strong> kaydetti\u011Finizde platformun kendi kod \u00FCretimi arka planda \u00E7al\u0131\u015F\u0131r ve yeniden \u00FCretilen model ile DTO s\u0131n\u0131flar\u0131 an\u0131nda y\u00FCklenir; ant clean all ve yeniden ba\u015Flatma gerekmez. Yeni bir attribute h\u00E2l\u00E2 veritaban\u0131 s\u00FCtunu ister, o y\u00FCzden Reclazz veritaban\u0131n\u0131za dokunmak yerine HAC &gt; Update Running System \u00E7al\u0131\u015Ft\u0131rman\u0131z\u0131 hat\u0131rlat\u0131r. Bunlara ek olarak: interceptor yenileme (Validate, Prepare, Load, Remove) ile birlikte \u00E7al\u0131\u015Fan sunucuya uygulanan property de\u011Fi\u015Fiklikleri, \u00E7al\u0131\u015Fan Log4j2 ba\u011Flam\u0131na uygulanan log seviyeleri, locales dosyalar\u0131ndan yeniden okunan tip ve enum metinleri, tazelenen backoffice label\'lar\u0131, <strong>*-spring.xml</strong> yenileme, ImpEx otomatik import ve uzant\u0131-bilin\u00E7li s\u0131n\u0131f izleme.',
        'faq.q7': 'JetBrains Runtime gerekli mi?',
        'faq.a7': 'Gerekli de\u011Fil. Yap\u0131sal de\u011Fi\u015Fiklikler (metot ve alan ekleme/kald\u0131rma) desteklenen JDK 17+ yollar\u0131nda \u00E7al\u0131\u015F\u0131r. JetBrains Runtime veya DCEVM ek olarak bu yeni \u00FCyeleri reflection\'a ve a\u00E7\u0131l\u0131\u015Fta kurulan \u00F6nbelleklere g\u00F6r\u00FCn\u00FCr k\u0131lar. Reclazz hangi runtime kulland\u0131\u011F\u0131n\u0131z\u0131 otomatik alg\u0131lar.',
        'faq.q8': 'SAP Commerce i\u00E7in JRebel\'e \u00FCcretsiz bir alternatif var m\u0131?',
        'faq.a8': 'Reclazz bunlardan biri ve Apache 2.0 ile a\u00E7\u0131k kaynak; lisans sunucusu yok, ki\u015Fi ba\u015F\u0131 \u00FCcret yok. Java s\u0131n\u0131flar\u0131n\u0131, eklenen metot ve alanlar dahil, JDK 17 ve \u00FCzerinde yeniden y\u00FCkl\u00FCyor. SAP Commerce taraf\u0131nda ise yaln\u0131z Java ile s\u0131n\u0131rl\u0131 kalm\u0131yor: bir *-items.xml ya da *-beans.xml kaydetti\u011Finizde model ve DTO s\u0131n\u0131flar\u0131 yeniden \u00FCretilip y\u00FCkleniyor, property de\u011Fi\u015Fiklikleri \u00E7al\u0131\u015Fan sunucuya ula\u015F\u0131yor, log seviyeleri restarts\u0131z de\u011Fi\u015Fiyor, locales metinleri ve backoffice etiketleri tazeleniyor, *-spring.xml canl\u0131 ba\u011Flama uygulan\u0131yor ve yeni yazd\u0131\u011F\u0131n\u0131z bir u\u00E7 sonraki istekte cevap veriyor.',
        'faq.q9': 'items.xml de\u011Fi\u015Fikli\u011Fini ant clean all \u00E7al\u0131\u015Ft\u0131rmadan nas\u0131l y\u00FCklerim?',
        'faq.a9': 'Reclazz a\u00E7\u0131kken dosyay\u0131 kaydetmeniz yeterli. Platformun kendi kod \u00FCretimi arka planda \u00E7al\u0131\u015F\u0131yor ve yeniden \u00FCretilen model ile DTO s\u0131n\u0131flar\u0131 ayakta duran sunucuya y\u00FCkleniyor; yani tam yeniden derleme ve yeniden ba\u015Flatma yerine saniyeler i\u00E7inde canl\u0131 oluyor. Yeni bir attribute h\u00E2l\u00E2 veritaban\u0131 s\u00FCtunu ister, o y\u00FCzden Reclazz size HAC > Platform > Update Running System \u00E7al\u0131\u015Ft\u0131rman\u0131z\u0131 hat\u0131rlat\u0131r, veritaban\u0131n\u0131zda kendisi DDL \u00E7al\u0131\u015Ft\u0131rmaz. \u00C7al\u0131\u015Fan bir SAP Commerce 2211 sunucusunda \u00F6l\u00E7\u00FCld\u00FC.',
        'cta.title_html': 'Uygulama Yeniden Ba\u015Flatmalar\u0131na<br><span class="text-gradient">Zaman Harcamay\u0131 B\u0131rak\u0131n</span>',
        'cta.subtitle': "Desteklenen reload işlemleriyle geliştirme akışınıza devam edin. Kurulum, framework koşulları ve yeniden başlatma gereksinimleri için dokümantasyonu inceleyin.",
        'cta.install': 'Marketplace\'den Y\u00FCkle',
        'cta.github': 'Kaynak Kodu GitHub\'da',
        'features.added.title': "Eklediğiniz Metotlar Bulunur",
        'features.added.desc': "Standart bir JDK'da eklediğiniz metot gizli bir companion sınıfında yaşar; framework'ün taraması onu göremez. Reclazz bunun yerine framework'e kaydedilen metadata'yı verir: yeni bir @Bean fabrikası ürününü kaydeder, koleksiyon, Optional, provider ve @Value argümanlarını Spring çözer; yeni bir @PostConstruct ya da @PreDestroy bir sonraki örnekte çalışır ve destroy callback'ini korur; yeni bir @Transactional, @Cacheable, @Async ya da @PreAuthorize/@PostAuthorize servis metodu gerçek interceptor'lardan geçer, bileşik biçimler ve hem Spring 5 hem Spring 6 güvenliği dahil; eklenen bir @KafkaListener ya da @RabbitListener konu, grup ya da kuyruk adı #{SpEL} ifadesi olabilir, bir @JmsListener @SendTo ile yanıt verebilir; eklenen bir @Bean fabrikası doğrudan @Conditional ya da Boot @ConditionalOnProperty, @ConditionalOnBean ve @ConditionalOnMissingBean koşullarını dikkate alır. Adaptörü olmayan bir şekil sessizce atlanmaz, restart gerektirdiği söylenerek adlandırılır.",
        'features.brokers.title': "Mesaj Dinleyicileri",
        'features.brokers.desc': "Çalışan bir component'e eklediğiniz @KafkaListener, @JmsListener ya da @RabbitListener uygulamanın kendi dinleyici işlemcisi ve container fabrikası üzerinden kaydolur. Bean yeniden yaratılmadan önce mevcut consumer'ları durdurulup beklenir, böylece bir kuyruk asla iki kez tüketilmez; düzenlemeler, topic ya da kuyruk değişiklikleri, kaldırma ve geri getirme sonraki kayıtları izler. Gömülü Kafka ve ActiveMQ broker'ları ile gerçek bir RabbitMQ container'ına karşı doğrulandı.",
        'compare.added_bean': "Açılıştan sonra eklenen @Bean metodu",
        'compare.added_handlers': "Açılıştan sonra eklenen handler ya da callback metodu",
        'compare.brokers': "Açılıştan sonra eklenen mesaj dinleyicisi",
        'compare.jackson': "Açılıştan sonra eklenen Jackson property'si",
        'compare.config': "Properties ve YAML kaydedilen dosyayı izler",
        'compare.xml': "Spring XML constructor, factory ve init/destroy düzenlemeleri",
        'faq.q10': "Çalışan bir bean'e metot ekledim. Spring, Jackson ya da mesaj broker'ım onu bulur mu?",
        'faq.a10': "Standart bir JDK'da metot gizli bir companion sınıfında yaşar; reflection tabanlı bir taramanın göremediği tam olarak budur. Bu yüzden Reclazz her framework'e kaydedilen metadata'yı bir adaptör üzerinden verir. Yeni bir endpoint, exception handler, binder ya da model attribute metodu bir sonraki istekte cevap verir; yeni bir @Bean, @Scheduled, @EventListener, @TransactionalEventListener, @PostConstruct ya da @PreDestroy kaydolur; yeni bir @KafkaListener, @JmsListener ya da @RabbitListener önceki consumer durdurulduktan sonra tüketir; yeni bir Jackson getter, setter ya da alanı okunup yazılır. Her biri framework'e karşı gerçek bir testtir; adaptörü olmayan bir şekil restart gerektirdiği söylenerek adlandırılır.",
    }
};

let currentLang = 'en';
try { currentLang = localStorage.getItem('reclazz-lang') === 'tr' ? 'tr' : 'en'; } catch (_) { /* Keep English without storage. */ }
const langToggle = document.getElementById('langToggle');
const originalTexts = {};

// Store original English texts
document.querySelectorAll('[data-i18n]').forEach(el => {
    originalTexts[el.getAttribute('data-i18n')] = el.innerHTML;
});

function setLang(lang) {
    currentLang = lang;
    try { localStorage.setItem('reclazz-lang', lang); } catch (_) { /* Keep the in-page language. */ }
    langToggle.textContent = lang === 'en' ? 'TR' : 'EN';
    langToggle.setAttribute('aria-label', lang === 'en' ? 'Switch language to Turkish' : 'Switch language to English');
    document.documentElement.lang = lang;

    document.querySelectorAll('[data-i18n]').forEach(el => {
        const key = el.getAttribute('data-i18n');
        if (lang === 'tr' && translations.tr[key]) {
            // Preserve arrow spans in FAQ questions
            const arrow = el.querySelector('.faq-arrow');
            el.innerHTML = translations.tr[key];
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

})();
