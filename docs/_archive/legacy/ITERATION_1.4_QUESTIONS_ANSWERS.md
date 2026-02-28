# Iteration 1.4 - Questions & Answers

## Q1. Should the icon start BOTH backend and desktop, or do we want two icons: "Jarvis" and "Jarvis Backend"?
**Answer:** One icon starts everything (backend + desktop).
**Rationale:** 
- Simpler UX for end user
- Launcher can detect if backend is already running and skip startup
- Single entry point = less confusion

## Q2. Preferred launcher tech: JavaFX (reuse JVM stack) or native minimal (GTK/python)?
**Answer:** JavaFX launcher module (consistent with desktop-client).
**Rationale:**
- Reuse existing JVM/JavaFX stack
- Consistent look & feel
- Easier to maintain (one tech stack)
- Can share code with desktop-client if needed

## Q3. Do we require a "Stop Jarvis" icon/menu entry?
**Answer:** Yes, separate desktop action (already exists in jarvis.desktop).
**Rationale:**
- User needs graceful shutdown
- Current jarvis.desktop already has "Stop" action
- Will update to use launcher or minimal wrapper (no terminal)

## Q4. Where to store PID/lock files?
**Answer:** ~/.jarvis/run/
**Rationale:**
- Centralized location
- Easy to clean up
- Survives restarts (directory persists)

## Q5. Do we require autostart on login?
**Answer:** Not now, but design ready.
**Rationale:**
- Keep it simple for MVP
- Can add later via ~/.config/autostart/jarvis.desktop
- Launcher will support it (no changes needed)


