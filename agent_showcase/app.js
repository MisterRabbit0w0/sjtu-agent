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


/* ─── Real-scene chat: messages appear one-by-one as section enters view ── */
(function initSceneChat() {
    const chat = document.querySelector('.tg-chat-scroll');
    if (!chat) return;

    const items = Array.from(chat.children);
    // hide all items initially
    items.forEach(el => {
        el.style.opacity = '0';
        el.style.transform = 'translateY(12px)';
        el.style.transition = 'opacity .38s ease, transform .38s ease';
    });

    let triggered = false;
    function reveal() {
        if (triggered) return;
        triggered = true;
        items.forEach((el, i) => {
            setTimeout(() => {
                el.style.opacity = '1';
                el.style.transform = 'none';
                // scroll the container to bottom as each message appears
                chat.scrollTo({ top: chat.scrollHeight, behavior: 'smooth' });
            }, 200 + i * 450);
        });
    }

    if (!('IntersectionObserver' in window)) { reveal(); return; }
    const io = new IntersectionObserver(entries => {
        if (entries[0].isIntersecting) { reveal(); io.disconnect(); }
    }, { threshold: .25 });
    const section = document.getElementById('real-scene');
    if (section) io.observe(section);
})();


/* ─── Parallax: phone frames drift slightly on scroll ───────────────────── */
(function initParallax() {
    const frames = document.querySelectorAll('.phone-frame, .phone-frame-lg');
    if (!frames.length || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;

    let ticking = false;
    window.addEventListener('scroll', () => {
        if (ticking) return;
        ticking = true;
        requestAnimationFrame(() => {
            const scrollY = window.scrollY;
            frames.forEach((frame, i) => {
                const dir = i % 2 === 0 ? 1 : -1;
                const rect = frame.getBoundingClientRect();
                const centerY = rect.top + rect.height / 2 - window.innerHeight / 2;
                frame.style.transform = `translateY(${centerY * -0.035 * dir}px)`;
            });
            ticking = false;
        });
    }, { passive: true });
})();


/* ─── Button ripple effect ───────────────────────────────────────────────── */
(function initRipple() {
    function addRipple(e) {
        const btn = e.currentTarget;
        const rect = btn.getBoundingClientRect();
        const ripple = document.createElement('span');
        const size = Math.max(rect.width, rect.height) * 1.8;
        ripple.style.cssText = `
            position:absolute;width:${size}px;height:${size}px;
            left:${e.clientX - rect.left - size / 2}px;
            top:${e.clientY - rect.top - size / 2}px;
            border-radius:50%;background:rgba(255,255,255,.18);
            transform:scale(0);pointer-events:none;
            animation:ripple-out .55s ease forwards;
        `;
        btn.style.position = 'relative';
        btn.style.overflow = 'hidden';
        btn.appendChild(ripple);
        ripple.addEventListener('animationend', () => ripple.remove());
    }

    document.querySelectorAll('.primary-button, .secondary-button, .header-cta').forEach(btn => {
        btn.addEventListener('click', addRipple);
    });

    // inject keyframe once
    if (!document.getElementById('ripple-style')) {
        const style = document.createElement('style');
        style.id = 'ripple-style';
        style.textContent = '@keyframes ripple-out{to{transform:scale(1);opacity:0}}';
        document.head.appendChild(style);
    }
})();


/* ─── Feature card 3-D tilt on mouse-move ───────────────────────────────── */
(function initCardTilt() {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    if ('ontouchstart' in window) return; // skip on touch devices

    const STRENGTH = 6; // degrees

    document.querySelectorAll('.feature-card, .boundary-card').forEach(card => {
        card.addEventListener('mousemove', e => {
            const rect = card.getBoundingClientRect();
            const cx = rect.left + rect.width  / 2;
            const cy = rect.top  + rect.height / 2;
            const rx = ((e.clientY - cy) / (rect.height / 2)) * -STRENGTH;
            const ry = ((e.clientX - cx) / (rect.width  / 2)) *  STRENGTH;
            card.style.transform = `perspective(800px) rotateX(${rx}deg) rotateY(${ry}deg) translateY(-3px)`;
            card.style.transition = 'transform .05s linear, box-shadow .05s linear';
        });
        card.addEventListener('mouseleave', () => {
            card.style.transform = '';
            card.style.transition = 'transform .35s ease, box-shadow .35s ease, border-color .2s';
        });
    });
})();


/* ─── Scene points: staggered slide-in when section visible ─────────────── */
(function initScenePoints() {
    const points = document.querySelectorAll('.scene-points li');
    if (!points.length) return;

    points.forEach(li => {
        li.style.opacity = '0';
        li.style.transform = 'translateX(-18px)';
        li.style.transition = 'opacity .4s ease, transform .4s ease';
    });

    if (!('IntersectionObserver' in window)) {
        points.forEach(li => { li.style.opacity='1'; li.style.transform='none'; });
        return;
    }

    let done = false;
    const io = new IntersectionObserver(entries => {
        if (done || !entries[0].isIntersecting) return;
        done = true;
        points.forEach((li, i) => {
            setTimeout(() => {
                li.style.opacity = '1';
                li.style.transform = 'none';
            }, i * 160);
        });
        io.disconnect();
    }, { threshold: .3 });

    const section = document.getElementById('real-scene');
    if (section) io.observe(section);
})();


/* ─── Smooth cursor blink on tg-report-title (typewriter feel) ──────────── */
(function initTypingCursor() {
    const title = document.querySelector('#tg-chat .tg-report-title');
    if (!title) return;
    const cursor = document.createElement('span');
    cursor.style.cssText = `
        display:inline-block;width:2px;height:.9em;
        background:#4f8cff;margin-left:3px;
        vertical-align:middle;border-radius:1px;
        animation:blink-cur .9s step-end infinite;
    `;
    title.appendChild(cursor);

    if (!document.getElementById('cursor-style')) {
        const s = document.createElement('style');
        s.id = 'cursor-style';
        s.textContent = '@keyframes blink-cur{0%,100%{opacity:1}50%{opacity:0}}';
        document.head.appendChild(s);
    }
})();
