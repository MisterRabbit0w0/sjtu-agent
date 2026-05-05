/* ─── Particle background ──────────────────────────────────────────────── */
(function initParticles() {
    const canvas = document.getElementById('bg-canvas');
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    let W, H, particles, raf;
    const COUNT = 72;
    const COLORS = ['#4f8cff','#a78bfa','#34d399','#60a5fa'];

    function resize() {
        W = canvas.width  = window.innerWidth;
        H = canvas.height = window.innerHeight;
    }

    function rand(a, b) { return a + Math.random() * (b - a); }

    function create() {
        particles = Array.from({ length: COUNT }, () => ({
            x: rand(0, W), y: rand(0, H),
            r: rand(1, 2.2),
            vx: rand(-.18, .18), vy: rand(-.12, .12),
            color: COLORS[Math.floor(Math.random() * COLORS.length)],
            alpha: rand(.18, .55),
        }));
    }

    function draw() {
        ctx.clearRect(0, 0, W, H);
        particles.forEach(p => {
            ctx.beginPath();
            ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
            ctx.fillStyle = p.color;
            ctx.globalAlpha = p.alpha;
            ctx.fill();
        });
        ctx.globalAlpha = 1;

        // Draw connections
        ctx.lineWidth = .6;
        for (let i = 0; i < particles.length; i++) {
            for (let j = i + 1; j < particles.length; j++) {
                const a = particles[i], b = particles[j];
                const dx = a.x - b.x, dy = a.y - b.y;
                const dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < 130) {
                    ctx.beginPath();
                    ctx.moveTo(a.x, a.y);
                    ctx.lineTo(b.x, b.y);
                    ctx.strokeStyle = '#4f8cff';
                    ctx.globalAlpha = (1 - dist / 130) * .08;
                    ctx.stroke();
                    ctx.globalAlpha = 1;
                }
            }
        }
    }

    function update() {
        particles.forEach(p => {
            p.x += p.vx; p.y += p.vy;
            if (p.x < -10) p.x = W + 10;
            if (p.x > W + 10) p.x = -10;
            if (p.y < -10) p.y = H + 10;
            if (p.y > H + 10) p.y = -10;
        });
    }

    function loop() { update(); draw(); raf = requestAnimationFrame(loop); }

    resize(); create(); loop();
    window.addEventListener('resize', () => { resize(); create(); });
})();


/* ─── Scroll reveal ──────────────────────────────────────────────────────── */
(function initReveal() {
    const items = document.querySelectorAll('.reveal-up');
    if (!('IntersectionObserver' in window)) {
        items.forEach(el => el.classList.add('is-visible'));
        return;
    }
    const io = new IntersectionObserver(entries => {
        entries.forEach(e => {
            if (e.isIntersecting) {
                e.target.classList.add('is-visible');
                io.unobserve(e.target);
            }
        });
    }, { threshold: .10, rootMargin: '0px 0px -40px 0px' });
    items.forEach(el => io.observe(el));
})();


/* ─── Counter animation ─────────────────────────────────────────────────── */
(function initCounters() {
    const counters = document.querySelectorAll('.stat-number[data-target]');
    if (!counters.length) return;

    function animateCounter(el) {
        const target = parseInt(el.dataset.target, 10);
        if (target === 0) { el.textContent = '0'; return; }
        const duration = 1400;
        const start = performance.now();
        function step(now) {
            const t = Math.min((now - start) / duration, 1);
            const ease = 1 - Math.pow(1 - t, 3);
            el.textContent = Math.round(ease * target);
            if (t < 1) requestAnimationFrame(step);
            else el.textContent = target;
        }
        requestAnimationFrame(step);
    }

    if (!('IntersectionObserver' in window)) {
        counters.forEach(animateCounter); return;
    }
    const io = new IntersectionObserver(entries => {
        entries.forEach(e => {
            if (e.isIntersecting) { animateCounter(e.target); io.unobserve(e.target); }
        });
    }, { threshold: .5 });
    counters.forEach(el => io.observe(el));
})();


/* ─── Sticky header shadow on scroll ────────────────────────────────────── */
(function initHeaderShadow() {
    const header = document.getElementById('site-header');
    if (!header) return;
    window.addEventListener('scroll', () => {
        header.style.boxShadow = window.scrollY > 32
            ? '0 8px 40px rgba(0,0,0,.6), inset 0 1px 0 rgba(255,255,255,.06)'
            : '0 4px 32px rgba(0,0,0,.4), inset 0 1px 0 rgba(255,255,255,.06)';
    }, { passive: true });
})();


/* ─── Active nav link highlight ─────────────────────────────────────────── */
(function initNavHighlight() {
    const sections = document.querySelectorAll('section[id]');
    const links = document.querySelectorAll('.nav-link[href^="#"]');
    if (!sections.length || !links.length) return;

    const io = new IntersectionObserver(entries => {
        entries.forEach(e => {
            if (e.isIntersecting) {
                links.forEach(l => l.style.color = '');
                const active = document.querySelector(`.nav-link[href="#${e.target.id}"]`);
                if (active) active.style.color = 'var(--accent)';
            }
        });
    }, { threshold: .4 });
    sections.forEach(s => io.observe(s));
})();


/* ─── FAQ smooth open/close ─────────────────────────────────────────────── */
(function initFaq() {
    document.querySelectorAll('.faq-item').forEach(item => {
        item.addEventListener('toggle', () => {
            if (item.open) {
                item.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            }
        });
    });
})();
