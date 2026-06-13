# Bang! Howdy modernization — Epics

The modernization is organized into **Epics**, each a self-contained campaign with its own ordered
phases and acceptance bar.

## Epic 1 — jME3 / LWJGL3 engine upgrade  *(in progress)*
Move the running game off the vendored 2005-era jMonkeyEngine fork + LWJGL2 + libGDX onto stock
**jMonkeyEngine 3.9 + LWJGL3**. Plan + phase status: [`jme3-cutover-plan.md`](jme3-cutover-plan.md)
(Phases 1–8). Predecessor groundwork (migration map, model loader, board converter, BUI seam) is
already merged to master.

## Epic 2 — Three Rings code consolidation  *(planned)*
Bring the Three Rings / samskivert dependency stack under our own control — fork every upstream lib
as insurance against the Three Rings GitHub/studio going away, then optionally vendor, combine, and
modernize the layers to align with the Epic-1 jME3/LWJGL3 world. Plan + phases:
[`epic2-threerings-consolidation.md`](epic2-threerings-consolidation.md).

Epic 2 depends on Epic 1 only loosely (its Phase 1 — dependency-control mechanism — can land any
time; the heavier vendoring/combining phases are best done once the game is stable on jME3).
