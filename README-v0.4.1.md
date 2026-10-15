# Bubble BDS Host v0.4.1 — Fullscreen + Bottom Navigation

Mudanças desta revisão:
- tela principal em fullscreen, ocultando a barra de status/notificações;
- barra de navegação do Android continua disponível, mas o conteúdo respeita o inset inferior;
- botões, seletor e formulários ficam sempre acima da barra Android;
- campos Nome do mundo, Seed e Comando ficaram ~60% mais altos (76dp);
- nova barra de navegação inferior da Bubble com duas abas: Início e Console;
- Início mantém mundo, seed, modo, cheats, IP e botão iniciar/parar;
- Console virou uma tela separada e ocupa toda a área disponível;
- Console agora exibe log completo (até ~50k caracteres) e permite digitar comandos limitados;
- auto-scroll do console não rouba foco enquanto o usuário digita;
- teclado usa adjustResize e o layout também respeita o inset do IME;
- instalador também ganhou proteção contra sobreposição da barra inferior do Android;
- mantém Foreground Service, Wake Lock, resume do Box64 e seleção Stable/Preview da v0.4.0.

Versão: 0.4.1
versionCode: 10
