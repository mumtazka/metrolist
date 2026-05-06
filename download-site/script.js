document.addEventListener('DOMContentLoaded', () => {
    // Reveal Animations using Intersection Observer
    const observerOptions = {
        root: null,
        rootMargin: '0px',
        threshold: 0.1
    };

    const observer = new IntersectionObserver((entries, observer) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.classList.add('visible');
                // Optional: unobserve after animating
                observer.unobserve(entry.target);
            }
        });
    }, observerOptions);

    document.querySelectorAll('.fade-up').forEach(el => observer.observe(el));

    // GitHub API logic
    const REPO = 'mumtazka/metrolist';

    const platforms = [
        { id: 'windows', name: 'Windows', icon: 'ri-windows-fill', match: ['.exe', 'Windows'] },
        { id: 'linux', name: 'Linux', icon: 'ri-ubuntu-fill', match: ['.deb', 'Linux'] },
        { id: 'android', name: 'Android', icon: 'ri-android-fill', match: ['.apk', 'Android'] }
    ];

    function detectOS() {
        const userAgent = window.navigator.userAgent.toLowerCase();
        if (userAgent.includes('win')) return 'windows';
        if (userAgent.includes('linux') && !userAgent.includes('android')) return 'linux';
        if (userAgent.includes('android')) return 'android';
        return 'unknown';
    }

    const currentOSID = detectOS();
    const currentOS = platforms.find(p => p.id === currentOSID);

    // Elements
    const primaryWrapper = document.getElementById('primary-wrapper');
    const osNameEl = document.getElementById('os-name');
    const primaryBtn = document.getElementById('primary-btn');
    const platformsGrid = document.getElementById('platforms-grid');

    async function fetchLatestReleaseAssets() {
        try {
            const response = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`);
            if (!response.ok) throw new Error('Failed to fetch API response');
            const data = await response.json();
            return data.assets || [];
        } catch (error) {
            console.error('Error fetching release:', error);
            // Graceful fallback to repo releases page
            return null;
        }
    }

    async function init() {
        const assets = await fetchLatestReleaseAssets();

        const getAssetUrl = (platform) => {
            if (!assets) return `https://github.com/${REPO}/releases/latest`;
            const asset = assets.find(a => platform.match.some(m => a.name.includes(m)));
            return asset ? asset.browser_download_url : `https://github.com/${REPO}/releases/latest`;
        };

        if (currentOS) {
            primaryWrapper.classList.remove('hidden');
            osNameEl.textContent = currentOS.name;
            const primaryUrl = getAssetUrl(currentOS);

            primaryBtn.onclick = () => {
                window.location.href = primaryUrl;
            };
        }

        platforms.forEach(platform => {
            if (platform.id === currentOSID) return;

            const url = getAssetUrl(platform);
            const card = document.createElement('div');
            card.className = 'glass-card platform-card';

            card.innerHTML = `
                <div class="header-row">
                    <i class="${platform.icon} plat-icon"></i>
                </div>
                <div class="plat-name">${platform.name}</div>
                <a class="dl-link" onclick="window.location.href='${url}'">
                    Download <i class="ri-arrow-right-line"></i>
                </a>
            `;

            card.addEventListener('mousemove', (e) => {
                const rect = card.getBoundingClientRect();
                const x = e.clientX - rect.left;
                const y = e.clientY - rect.top;
                card.style.background = `rgba(255, 255, 255, 0.05) radial-gradient(circle at ${x}px ${y}px, rgba(255,255,255,0.1) 0%, transparent 60%)`;
            });
            card.addEventListener('mouseleave', () => {
                card.style.background = 'linear-gradient(180deg, rgba(255,255,255,0.03) 0%, rgba(255,255,255,0.01) 100%)';
            });

            platformsGrid.appendChild(card);
        });
    }

    init();
});
