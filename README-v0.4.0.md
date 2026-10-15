# Bubble BDS Host v0.4.0

- ícone interno novo fornecido pelo usuário; o ícone externo/launcher é preservado;
- instalador sem terminal, com cards explicativos;
- duas barras: processo atual + instalação geral;
- seletor Atual/Alpha ou Preview/Beta;
- instalação em Foreground Service + Wake Lock;
- rotação não reinicia instalação;
- resume incremental do Box64 preservado;
- botão Iniciar/Parar acompanha o estado real do servidor;
- notificação expandida com uptime, mundo, IP, porta, modo e versão;
- painel mostra versão, mundo, modo, endereço e add-ons;
- console simplificado, comandos limitados e auto-scroll sem roubar foco;
- assinatura persistente via GitHub Secrets.

## Atualizações sem reinstalar

O Android precisa da mesma `applicationId`, da mesma chave de assinatura e de um `versionCode`
crescente. A v0.4.0 cria uma chave persistente e a guarda fora do repositório, enviando-a aos
GitHub Secrets. A transição do APK debug antigo pode exigir uma última desinstalação. Depois da
v0.4.0, preserve `~/.bubble-signing` e os secrets para instalar versões futuras por cima sem
apagar Debian/Box64/BDS.
