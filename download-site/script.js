document.addEventListener('DOMContentLoaded', () => {
    // Connect to your GitHub repository
    const REPO = 'mumtazka/metrolist';

    const platforms = [
        { id: 'windows', name: 'Windows', icon: 'ri-windows-fill', text: 'Download for Windows', match: ['.msi', 'Windows'] },
        { id: 'mac', name: 'macOS', icon: 'ri-apple-fill', text: 'Download for macOS', match: ['.dmg', 'Mac', 'darwin'] },
        { id: 'linux', name: 'Linux', icon: 'ri-ubuntu-fill', text: 'Download for Linux', match: ['.deb', 'Linux'] },
        { id: 'android', name: 'Android', icon: 'ri-android-fill', text: 'Get on Android', match: ['.apk', 'Android'] },
        { id: 'ios', name: 'iOS', icon: 'ri-apple-fill', text: 'Get on iOS', match: [] }
    ];

    function detectOS() {
        const userAgent = window.navigator.userAgent.toLowerCase();
        if (userAgent.includes('win')) return 'windows';
        if (userAgent.includes('mac')) {
            if (navigator.maxTouchPoints > 0) return 'ios';
            return 'mac';
        }
        if (userAgent.includes('linux') && !userAgent.includes('android')) return 'linux';
        if (userAgent.includes('android')) return 'android';
        if (userAgent.includes('iphone') || userAgent.includes('ipad') || userAgent.includes('ipod')) return 'ios';
        return 'unknown';
    }

    const currentOSID = detectOS();
    const currentOS = platforms.find(p => p.id === currentOSID) || platforms[0];

    // UI Elements
    const osNameEl = document.getElementById('os-name');
    const primaryBtn = document.getElementById('primary-download-btn');
    const otherPlatformsContainer = document.getElementById('other-platforms-container');

    // Setup initial UI states
    if (currentOSID !== 'unknown') {
        osNameEl.textContent = currentOS.name;
        primaryBtn.innerHTML = `<i class="${currentOS.icon}"></i> ${currentOS.text}...`;
    } else {
        osNameEl.parentElement.textContent = 'Choose your platform below:';
        document.getElementById('primary-download-card').style.display = 'none';
    }

    // Fetch the latest release from GitHub API
    async function fetchLatestReleaseAssets() {
        try {
            const response = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`);
            if (!response.ok) throw new Error('No release found');
            const data = await response.json();
            return data.assets || [];
        } catch (error) {
            console.error('Error fetching release:', error);
            return [];
        }
    }

    // Initialize logic
    async function init() {
        const assets = await fetchLatestReleaseAssets();

        // Match assets based on platform keywords
        const getAssetUrl = (platform) => {
            if (assets.length === 0) return '#';
            const asset = assets.find(a => platform.match.some(m => a.name.includes(m)));
            return asset ? asset.browser_download_url : '#';
        };

        const primaryUrl = getAssetUrl(currentOS);

        // Update primary button
        if (currentOSID !== 'unknown') {
            primaryBtn.innerHTML = `<i class="${currentOS.icon}"></i> ${currentOS.text}`;
            primaryBtn.onclick = () => {
                if (primaryUrl !== '#') {
                    window.location.href = primaryUrl;
                } else {
                    alert(`The ${currentOS.name} file is missing from the latest GitHub release. Check again later!`);
                }
            };
        }

        // Render other platforms
        platforms.forEach(platform => {
            if (platform.id === currentOSID && currentOSID !== 'unknown') return;

            const url = getAssetUrl(platform);
            const card = document.createElement('div');
            card.className = 'platform-card';
            card.innerHTML = `
                <i class="${platform.icon} platform-icon"></i>
                <div class="platform-name">${platform.name}</div>
                <button class="btn btn-secondary" style="padding: 0.5rem 1rem; font-size: 0.9rem;">
                    <i class="ri-download-line"></i> Download
                </button>
            `;

            const btn = card.querySelector('.btn');
            btn.onclick = () => {
                if (url !== '#') {
                    window.location.href = url;
                } else {
                    alert(`The ${platform.name} file is missing from the latest GitHub release. Check again later!`);
                }
            };
            otherPlatformsContainer.appendChild(card);
        });
    }

    init();
});
