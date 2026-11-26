(() => {
    const pictures = document.querySelectorAll('.wizard-picture');
    function updatePictures() {
        const dark = document.documentElement.dataset.theme === 'dark';
        for (const picture of pictures) {
            const source = picture.querySelector('source');
            source.media = dark ? 'all' : 'not all';
            const link = picture.parentElement;
            if (link.tagName === 'A') link.href = dark ? source.srcset : picture.querySelector('img').src;
        }
    }
    updatePictures();
    new MutationObserver(updatePictures).observe(document.documentElement, {
        attributes: true, attributeFilter: ['data-theme']
    });

    const steps = document.querySelector('.home-page .wizard-steps');
    if (steps) {
        const entries = Array.from(steps.children, (step, index) => {
            const heading = step.querySelector('h3');
            const figure = step.querySelector('figure');
            const description = step.querySelector('p');
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'wizard-select';
            button.append(...heading.childNodes);
            heading.append(button);
            figure.id = 'setup-image-' + index;
            description.id = 'setup-action-' + index;
            button.setAttribute('aria-controls', figure.id + ' ' + description.id);
            return {button, figure, description};
        });
        function select(current) {
            for (const entry of entries) {
                const selected = entry === current;
                entry.button.setAttribute('aria-expanded', String(selected));
                entry.figure.hidden = !selected;
                entry.description.hidden = !selected;
            }
        }
        for (const entry of entries) entry.button.addEventListener('click', () => select(entry));
        steps.classList.add('enhanced');
        select(entries[0]);
    }

    const film = document.getElementById('reload-film');
    if (!film) return;
    // A responsive still also gives reduced-motion users an explicit play control.
    const cover = document.querySelector('.film-cover');
    const poster = cover.querySelector('img');
    const usePoster = () => { if (poster.currentSrc) film.poster = poster.currentSrc; };
    poster.addEventListener('load', usePoster);
    usePoster();
    film.tabIndex = -1;
    function revealFilm() {
        const transferFocus = document.activeElement === cover;
        cover.hidden = true;
        film.removeAttribute('tabindex');
        if (transferFocus) film.focus({preventScroll: true});
    }
    cover.addEventListener('click', event => {
        event.preventDefault();
        film.play().catch(revealFilm);
    });
    film.addEventListener('playing', revealFilm, {once: true});
    film.addEventListener('seeked', revealFilm, {once: true});
    const cues = Array.from(document.querySelectorAll('#recorded-events [data-seconds]'));
    function syncLog() {
        const current = cues.filter(cue => Number(cue.dataset.seconds) <= film.currentTime).pop();
        for (const cue of cues) {
            if (cue === current) cue.setAttribute('aria-current', 'true');
            else cue.removeAttribute('aria-current');
        }
    }
    // Some static hosts answer a Range request with the whole movie (HTTP 200).
    // Chrome can play that response but exposes no seekable interval on first visit.
    let objectUrl;
    let pendingMovie;
    let seekSequence = 0;
    function releaseMovie() {
        seekSequence++;
        pendingMovie = undefined;
        if (objectUrl) URL.revokeObjectURL(objectUrl);
        objectUrl = undefined;
        film.removeAttribute('src');
    }
    async function seekTo(seconds) {
        const sequence = ++seekSequence;
        const url = film.querySelector('source').src;
        if (film.readyState < HTMLMediaElement.HAVE_METADATA) {
            const ready = new Promise(resolve => film.addEventListener('loadedmetadata', resolve, {once: true}));
            film.preload = 'auto';
            film.load();
            await ready;
        }
        if (sequence !== seekSequence) return;
        const ranges = film.seekable;
        if (seconds && !Array.from({length: ranges.length}, (_, i) =>
                ranges.start(i) <= seconds && seconds <= ranges.end(i)).some(Boolean)) {
            pendingMovie ??= fetch(url).then(response => {
                if (!response.ok) throw new Error('Movie unavailable');
                return response.blob();
            }).catch(error => { pendingMovie = undefined; throw error; });
            const blob = await pendingMovie;
            if (sequence !== seekSequence) return;
            if (!objectUrl) {
                objectUrl = URL.createObjectURL(blob);
                const ready = new Promise(resolve => film.addEventListener('loadedmetadata', resolve, {once: true}));
                const playing = !film.paused;
                film.src = objectUrl;
                await ready;
                if (sequence !== seekSequence) return;
                if (playing) film.play().catch(() => {});
            }
        }
        film.currentTime = seconds;
        revealFilm();
        syncLog();
    }
    for (const cue of cues) {
        cue.querySelector('button').addEventListener('click', () => {
            seekTo(Number(cue.dataset.seconds)).catch(revealFilm);
        });
    }
    syncLog();
    film.addEventListener('timeupdate', syncLog);
    const reduced = matchMedia('(prefers-reduced-motion: reduce)');
    // Start downloading the movie after the high-priority still has painted.
    let posterPainted = false;
    function posterReady() {
        requestAnimationFrame(() => requestAnimationFrame(() => {
            posterPainted = true;
            playIfVisible();
        }));
    }
    if (document.readyState === 'complete') posterReady();
    else window.addEventListener('load', posterReady, {once: true});
    let onScreen = false;
    let manuallyPaused = false;
    function playIfVisible() {
        if (posterPainted && onScreen && !reduced.matches && !manuallyPaused) film.play().catch(() => {});
        else if (!onScreen || reduced.matches) film.pause();
    }
    film.addEventListener('pause', () => { if (onScreen && !reduced.matches) manuallyPaused = true; });
    film.addEventListener('play', () => { manuallyPaused = false; });
    new IntersectionObserver(entries => {
        onScreen = entries[0].isIntersecting;
        playIfVisible();
    }).observe(film);
    reduced.addEventListener('change', playIfVisible);

    function syncFilmLanguage() {
        const lang = document.documentElement.lang === 'tr' ? 'tr' : 'en';
        const suffix = lang === 'tr' ? '-tr' : '';
        const url = '/assets/setup/live-reload' + suffix + '.webm';
        const source = film.querySelector('source');
        if (source.getAttribute('src') === url) return;
        const position = film.currentTime;
        const resume = !film.paused;
        releaseMovie();
        const base = '/assets/setup/live-reload-';
        cover.href = url;
        document.querySelector('#film-caption a').href = url;
        cover.querySelector('picture source').srcset = base + 'phone' + suffix + '.webp';
        poster.src = base + 'poster' + suffix + '.webp';
        source.src = url;
        film.load();
        if (position || resume) {
            seekTo(position).then(() => {
                if (resume && film.querySelector('source').getAttribute('src') === url) {
                    film.play().catch(() => {});
                }
            }).catch(revealFilm);
        } else {
            playIfVisible();
        }
    }
    syncFilmLanguage();
    new MutationObserver(syncFilmLanguage).observe(document.documentElement, {
        attributes: true, attributeFilter: ['lang']
    });
})();
