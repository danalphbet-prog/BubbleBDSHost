# Bubble BDS Host v0.4.2 — Startup Hotfix

Corrige crash imediato ao abrir em alguns aparelhos Android/Samsung.

Causa corrigida:
- WindowInsetsController era consultado antes de a DecorView estar anexada à janela.

Mudança:
- só acessa WindowInsetsController depois de `decor.isAttachedToWindow()`;
- mantém fullscreen;
- mantém navegação inferior e safe-area;
- mantém assinatura persistente;
- versionCode 11 / versionName 0.4.2.
