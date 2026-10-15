# Bubble BDS Host v0.3.1 — Box64 Resume

Atualização de robustez sem alterar o design v0.3.0.

- preserva /root/box64-src e /root/box64-src/build;
- valida o commit oficial antes de reutilizar o source;
- cmake é reexecutado incrementalmente;
- make -j2 reaproveita objetos já compilados;
- se a compilação parar, o próximo Instalar Tudo continua o build;
- clone inválido/corrompido é recriado automaticamente.

Fluxo preservado:
PRoot clássico → Debian Trixie ARM64 → Box64 local → BDS.
