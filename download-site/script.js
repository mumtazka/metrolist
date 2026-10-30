(() => {
    'use strict';

    const $ = (sel, ctx = document) => ctx.querySelector(sel);
    const $$ = (sel, ctx = document) => Array.from(ctx.querySelectorAll(sel));
    const clamp = (v, min, max) => Math.min(max, Math.max(min, v));
    const REDUCED_MOTION = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const FINE_POINTER = window.matchMedia('(hover: hover) and (pointer: fine)').matches;

    /* ================= Scroll reveal ================= */
    const revealObserver = new IntersectionObserver((entries, obs) => {
        entries.forEach((entry) => {
            if (entry.isIntersecting) {
                entry.target.classList.add('visible');
                obs.unobserve(entry.target);
            }
        });
    }, { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });

    $$('.reveal').forEach((el) => revealObserver.observe(el));

    /* ================= Navbar: scroll state, progress, section spy ================= */
    const navbar = $('#navbar');
    const progressBar = $('#nav-progress');
    let scrollTicking = false;

    function onScroll() {
        if (scrollTicking) return;
        scrollTicking = true;
        requestAnimationFrame(() => {
            const y = window.scrollY;
            navbar.classList.toggle('scrolled', y > 10);
            const max = document.documentElement.scrollHeight - window.innerHeight;
            progressBar.style.transform = `scaleX(${max > 0 ? clamp(y / max, 0, 1) : 0})`;
            scrollTicking = false;
        });
    }

    window.addEventListener('scroll', onScroll, { passive: true });
    onScroll();

    const spyLinks = $$('[data-spy]');
    const spySections = spyLinks
        .map((link) => $(link.getAttribute('href')))
        .filter(Boolean);

    if (spySections.length) {
        const spyObserver = new IntersectionObserver((entries) => {
            entries.forEach((entry) => {
                if (!entry.isIntersecting) return;
                spyLinks.forEach((link) => {
                    link.classList.toggle('active', link.getAttribute('href') === `#${entry.target.id}`);
                });
            });
        }, { rootMargin: '-40% 0px -55% 0px' });
        spySections.forEach((sec) => spyObserver.observe(sec));
    }

    /* ================= Pause ambient animations when tab hidden ================= */
    document.addEventListener('visibilitychange', () => {
        document.body.classList.toggle('tab-hidden', document.hidden);
    });

    /* ================= App window mockup: sidebar switching + greeting ================= */
    document.addEventListener('click', (e) => {
        const item = e.target.closest('.app-nav-item');
        if (!item) return;
        const sidebar = item.closest('.app-sidebar');
        if (!sidebar) return;
        $$('.app-nav-item', sidebar).forEach((btn) => btn.classList.remove('active'));
        item.classList.add('active');
    });

    (function setGreeting() {
        const hour = new Date().getHours();
        const text = hour < 5 ? 'Good evening'
            : hour < 12 ? 'Good morning'
                : hour < 18 ? 'Good afternoon'
                    : 'Good evening';
        $$('.app-greeting').forEach((el) => {
            el.textContent = text;
        });
    })();

    /* ================= 3D tilt + spotlight (cards) ================= */
    function attachTilt(el) {
        if (el.dataset.tiltBound === '1') return;
        el.dataset.tiltBound = '1';
        const maxTilt = 7;
        el.addEventListener('mousemove', (e) => {
            const rect = el.getBoundingClientRect();
            const px = (e.clientX - rect.left) / rect.width;
            const py = (e.clientY - rect.top) / rect.height;
            el.style.setProperty('--ry', `${(px - 0.5) * maxTilt * 2}deg`);
            el.style.setProperty('--rx', `${(0.5 - py) * maxTilt * 2}deg`);
            el.style.setProperty('--mx', `${px * 100}%`);
            el.style.setProperty('--my', `${py * 100}%`);
        });
        el.addEventListener('mouseleave', () => {
            el.style.setProperty('--rx', '0deg');
            el.style.setProperty('--ry', '0deg');
        });
    }

    if (FINE_POINTER && !REDUCED_MOTION) {
        $$('[data-tilt]').forEach(attachTilt);

        /* Hero: deeper tilt on the phone + parallax layers */
        const scene = $('#device-scene');
        const phone = $('#hero-phone');
        if (scene && phone) {
            const parallaxEls = $$('[data-parallax]');
            scene.addEventListener('mousemove', (e) => {
                const rect = scene.getBoundingClientRect();
                const px = (e.clientX - rect.left) / rect.width;
                const py = (e.clientY - rect.top) / rect.height;
                phone.style.setProperty('--ry', `${(px - 0.5) * 16}deg`);
                phone.style.setProperty('--rx', `${(0.5 - py) * 14}deg`);
                parallaxEls.forEach((el) => {
                    const depth = parseFloat(el.dataset.parallax) || 0;
                    el.style.setProperty('--px', `${(px - 0.5) * depth}px`);
                    el.style.setProperty('--py', `${(py - 0.5) * depth}px`);
                });
            });
            scene.addEventListener('mouseleave', () => {
                phone.style.setProperty('--rx', '0deg');
                phone.style.setProperty('--ry', '0deg');
                parallaxEls.forEach((el) => {
                    el.style.setProperty('--px', '0px');
                    el.style.setProperty('--py', '0px');
                });
            });
        }

        /* Magnetic buttons */
        $$('.magnetic').forEach((btn) => {
            btn.addEventListener('mousemove', (e) => {
                const rect = btn.getBoundingClientRect();
                const dx = e.clientX - (rect.left + rect.width / 2);
                const dy = e.clientY - (rect.top + rect.height / 2);
                btn.style.transform = `translate(${dx * 0.18}px, ${dy * 0.28 - 2}px)`;
            });
            btn.addEventListener('mouseleave', () => {
                btn.style.transform = '';
            });
        });
    }

    /* ================= Hero device switcher: desktop ↔ mobile 3D turn ================= */
    const deviceToggle = $('#device-toggle');
    if (deviceToggle) {
        const tabs = $$('.device-tab', deviceToggle);
        const pill = $('.toggle-pill', deviceToggle);
        const faces = $$('.device-face');
        const order = { desktop: 0, mobile: 1 };
        let current = 'mobile';
        let autoTimer = null;
        let userStopped = false;

        function movePill() {
            const active = $('.device-tab.active', deviceToggle);
            if (!active || !pill) return;
            pill.style.width = `${active.offsetWidth}px`;
            pill.style.transform = `translateX(${active.offsetLeft - 4}px)`;
        }

        function switchDevice(name, fromUser) {
            if (!(name in order) || name === current) return;
            const prev = current;
            current = name;

            tabs.forEach((tab) => {
                const on = tab.dataset.device === name;
                tab.classList.toggle('active', on);
                tab.setAttribute('aria-selected', String(on));
            });
            movePill();

            faces.forEach((face) => {
                const faceName = face.dataset.face;
                if (faceName === name) {
                    const enterFromLeft = order[name] < order[prev];
                    face.style.transition = 'none';
                    face.dataset.state = enterFromLeft ? 'off-left' : 'off-right';
                    void face.offsetHeight;
                    face.style.transition = '';
                    face.dataset.state = 'active';
                    face.setAttribute('aria-hidden', 'false');
                } else {
                    face.dataset.state = order[faceName] > order[name] ? 'off-right' : 'off-left';
                    face.setAttribute('aria-hidden', 'true');
                }
            });

            if (fromUser) {
                userStopped = true;
                stopAutoCycle();
            }
        }

        function startAutoCycle() {
            if (REDUCED_MOTION || userStopped || autoTimer) return;
            autoTimer = window.setInterval(() => {
                switchDevice(current === 'mobile' ? 'desktop' : 'mobile', false);
            }, 4500);
        }

        function stopAutoCycle() {
            if (autoTimer) {
                window.clearInterval(autoTimer);
                autoTimer = null;
            }
        }

        tabs.forEach((tab) => {
            tab.addEventListener('click', () => switchDevice(tab.dataset.device, true));
        });
        window.addEventListener('resize', movePill);
        window.addEventListener('load', movePill);
        if (document.fonts && document.fonts.ready) {
            document.fonts.ready.then(movePill);
        }
        movePill();
        startAutoCycle();

        const stageEl = $('#device-stage');
        if (stageEl && 'IntersectionObserver' in window) {
            new IntersectionObserver((entries) => {
                entries.forEach((entry) => {
                    if (entry.isIntersecting) startAutoCycle();
                    else stopAutoCycle();
                });
            }, { threshold: 0.2 }).observe(stageEl);
        }

        document.addEventListener('visibilitychange', () => {
            if (document.hidden) stopAutoCycle();
            else startAutoCycle();
        });
    }

    /* ================= Screenshots: scroll-linked 3D rotation ================= */
    const shots = $$('[data-scroll-rotate]');
    if (shots.length && !REDUCED_MOTION) {
        let shotTicking = false;
        const updateShots = () => {
            const vh = window.innerHeight;
            shots.forEach((shot) => {
                const rect = shot.getBoundingClientRect();
                if (rect.bottom < 0 || rect.top > vh) return;
                const p = clamp((rect.top + rect.height / 2 - vh / 2) / vh, -1, 1);
                const frame = shot.querySelector('.shot-frame');
                if (frame) frame.style.setProperty('--srx', `${p * -8}deg`);
            });
            shotTicking = false;
        };
        window.addEventListener('scroll', () => {
            if (shotTicking) return;
            shotTicking = true;
            requestAnimationFrame(updateShots);
        }, { passive: true });
        updateShots();
    }

    /* ================= Toast ================= */
    let toastTimer = null;
    function showToast(message) {
        const toast = $('#toast');
        if (!toast) return;
        toast.textContent = message;
        toast.classList.add('show');
        window.clearTimeout(toastTimer);
        toastTimer = window.setTimeout(() => toast.classList.remove('show'), 2400);
    }

    /* ================= Downloads: platform switcher + GitHub release ================= */
    const REPO = 'mumtazka/metrolist';
    const RELEASES_URL = `https://github.com/${REPO}/releases/latest`;
    const CACHE_KEY = 'metrolist_release_v1';
    const CACHE_TTL = 60 * 60 * 1000;

    const PLATFORMS = {
        android: {
            name: 'Android',
            icon: 'ri-android-fill',
            desc: 'Download the APK, allow installs from unknown sources and press play.',
            test: (n) => /\.apk$/i.test(n) || /android/i.test(n),
        },
        windows: {
            name: 'Windows',
            icon: 'ri-windows-fill',
            desc: 'Run the installer, launch Metrolist and sign in optionally.',
            test: (n) => /\.exe$/i.test(n) || /windows/i.test(n),
        },
        linux: {
            name: 'Linux',
            icon: 'ri-ubuntu-fill',
            desc: 'Install the .deb package with your package manager of choice.',
            test: (n) => /\.deb$/i.test(n) || /linux/i.test(n),
        },
    };
    const PLATFORM_ORDER = ['android', 'windows', 'linux'];

    function detectOS() {
        const ua = navigator.userAgent.toLowerCase();
        if (/android/.test(ua)) return 'android';
        if (/win/.test(ua)) return 'windows';
        if (/linux|cros|x11/.test(ua)) return 'linux';
        return null;
    }

    function readCache() {
        try {
            const raw = localStorage.getItem(CACHE_KEY);
            if (!raw) return null;
            const parsed = JSON.parse(raw);
            if (!parsed || !parsed.t || !parsed.data) return null;
            return parsed;
        } catch {
            return null;
        }
    }

    function writeCache(data) {
        try {
            localStorage.setItem(CACHE_KEY, JSON.stringify({ t: Date.now(), data }));
        } catch {
            /* storage unavailable — fine */
        }
    }

    async function fetchRelease() {
        const cached = readCache();
        if (cached && Date.now() - cached.t < CACHE_TTL) return cached.data;
        try {
            const res = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, {
                headers: { Accept: 'application/vnd.github+json' },
            });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const data = await res.json();
            writeCache(data);
            return data;
        } catch {
            return cached ? cached.data : null;
        }
    }

    function fmtSize(bytes) {
        if (!bytes) return null;
        return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    }

    function fmtDate(iso) {
        if (!iso) return null;
        try {
            return new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
        } catch {
            return null;
        }
    }

    (function initDownloads() {
        const tabsWrap = $('#dl-tabs');
        if (!tabsWrap) return;

        const tabs = $$('.dl-tab', tabsWrap);
        const pill = $('.dl-tab-pill', tabsWrap);
        const iconWrap = $('#dl-icon-wrap');
        const iconEl = $('#dl-icon');
        const titleEl = $('#dl-title');
        const descEl = $('#dl-desc');
        const metaEl = $('#dl-meta');
        const btn = $('#dl-btn');
        const btnOs = $('#dl-btn-os');
        const copyBtn = $('#dl-copy');
        const recommendEl = $('#dl-recommend');
        const badgeText = $('#badge-text');

        const detected = detectOS();
        let current = detected || 'android';
        let release = null;

        function assetFor(id) {
            if (!release || !Array.isArray(release.assets)) return null;
            return release.assets.find((a) => PLATFORMS[id].test(a.name)) || null;
        }

        function urlFor(id) {
            const asset = assetFor(id);
            return asset ? asset.browser_download_url : RELEASES_URL;
        }

        function movePill() {
            const active = $('.dl-tab.active', tabsWrap);
            if (!active || !pill) return;
            pill.style.width = `${active.offsetWidth}px`;
            pill.style.transform = `translateX(${active.offsetLeft - 4}px)`;
        }

        function render(id, animate) {
            const platform = PLATFORMS[id];
            if (!platform) return;

            if (animate) {
                iconWrap.style.opacity = '0';
                titleEl.style.opacity = '0';
                descEl.style.opacity = '0';
                window.setTimeout(() => {
                    applyContent(platform, id);
                    iconWrap.style.opacity = '1';
                    titleEl.style.opacity = '1';
                    descEl.style.opacity = '1';
                }, 150);
            } else {
                applyContent(platform, id);
            }

            tabs.forEach((tab) => {
                const on = tab.dataset.platform === id;
                tab.classList.toggle('active', on);
                tab.setAttribute('aria-selected', String(on));
            });
            movePill();
        }

        function applyContent(platform, id) {
            iconWrap.dataset.platform = id;
            iconEl.className = platform.icon;
            titleEl.textContent = `Metrolist for ${platform.name}`;
            descEl.textContent = platform.desc;
            btnOs.textContent = platform.name;
            btn.href = urlFor(id);
            btn.setAttribute('aria-label', `Download Metrolist for ${platform.name}`);
            if (recommendEl) recommendEl.hidden = detected !== id;

            const asset = assetFor(id);
            const chips = [];
            if (release && release.tag_name) chips.push(`<span class="meta-chip">${release.tag_name}</span>`);
            const size = fmtSize(asset && asset.size);
            if (size) chips.push(`<span class="meta-chip">${size}</span>`);
            const date = fmtDate(release && release.published_at);
            if (date) chips.push(`<span>${date}</span>`);
            if (!chips.length) chips.push('<span>Latest stable release</span>');
            metaEl.innerHTML = chips.join('<span aria-hidden="true">·</span>');
        }

        tabs.forEach((tab) => {
            tab.addEventListener('click', () => {
                current = tab.dataset.platform;
                render(current, true);
            });
        });
        window.addEventListener('resize', movePill);
        window.addEventListener('load', movePill);
        if (document.fonts && document.fonts.ready) {
            document.fonts.ready.then(movePill);
        }

        copyBtn.addEventListener('click', async () => {
            try {
                await navigator.clipboard.writeText(urlFor(current));
                showToast('Download link copied');
            } catch {
                showToast('Could not copy — long-press the download button');
            }
        });

        render(current, false);

        fetchRelease().then((data) => {
            release = data;
            if (badgeText && data && data.tag_name) {
                badgeText.textContent = `${data.tag_name} is out now`;
            }
            render(current, false);
        });
    })();
})();
