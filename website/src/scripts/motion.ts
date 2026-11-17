// Page motion: scroll reveals, cursor spotlights, sticky-nav state and smooth
// FAQ accordions. Everything degrades gracefully with prefers-reduced-motion.

const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;

/* Scroll reveals: fade/slide each [data-reveal] into place, once. */
const revealTargets = document.querySelectorAll<HTMLElement>("[data-reveal]");
if (revealTargets.length && !reducedMotion && "IntersectionObserver" in window) {
  const io = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        entry.target.classList.add("revealed");
        io.unobserve(entry.target);
      }
    },
    { threshold: 0.12, rootMargin: "0px 0px -8% 0px" },
  );
  revealTargets.forEach((el) => io.observe(el));
} else {
  revealTargets.forEach((el) => el.classList.add("revealed"));
}

/* Spotlight cards: let a radial glow follow the cursor. */
if (!reducedMotion && matchMedia("(hover: hover)").matches) {
  const spots = document.querySelectorAll<HTMLElement>(".spotlight");
  spots.forEach((el) => {
    el.addEventListener("pointermove", (e) => {
      const rect = el.getBoundingClientRect();
      el.style.setProperty("--mx", `${e.clientX - rect.left}px`);
      el.style.setProperty("--my", `${e.clientY - rect.top}px`);
    });
  });
}

/* Sticky nav: condense once the page is scrolled. */
const header = document.querySelector<HTMLElement>("[data-nav]");
if (header) {
  const onScroll = () => header.classList.toggle("nav-scrolled", scrollY > 12);
  onScroll();
  addEventListener("scroll", onScroll, { passive: true });
}

/* FAQ: animate <details> open and closed instead of snapping. */
document.querySelectorAll<HTMLDetailsElement>("details[data-smooth]").forEach((details) => {
  const summary = details.querySelector("summary");
  const body = details.querySelector<HTMLElement>("[data-faq-body]");
  if (!summary || !body || reducedMotion) return;

  summary.addEventListener("click", (e) => {
    e.preventDefault();
    if (details.open) {
      // Closing: play the animation, then actually close.
      body.style.height = `${body.scrollHeight}px`;
      requestAnimationFrame(() => {
        body.style.height = "0px";
      });
      body.addEventListener(
        "transitionend",
        () => {
          details.open = false;
          body.style.height = "";
        },
        { once: true },
      );
    } else {
      // Opening: expand from 0 to the natural height.
      details.open = true;
      const target = body.scrollHeight;
      body.style.height = "0px";
      requestAnimationFrame(() => {
        body.style.height = `${target}px`;
      });
      body.addEventListener(
        "transitionend",
        () => {
          body.style.height = "";
        },
        { once: true },
      );
    }
  });
});
