# Bubble BDS Host v0.6.0 — Addon Bubble

Nova área de gerenciamento de add-ons na barra inferior.

## Addon Bubble
- terceira opção na navegação inferior: `Addon Bubble`;
- abas separadas para **Comportamento** e **Texturas**;
- importação externa por seletor de arquivos Android;
- aceita `.mcaddon`, `.mcpack` e arquivos ZIP compatíveis;
- detecta automaticamente Behavior Packs e Resource Packs através do `manifest.json`;
- um `.mcaddon` com mais de um pacote importa todos os packs reconhecidos;
- mostra nome, descrição, versão, estado e `pack_icon.png`/`pack_icon.jpg` quando disponível;
- permite **Ativar**, **Desativar** e **Remover** packs;
- alterações são feitas por mundo;
- quando o servidor está iniciando, ativo ou parando, a lista continua visível mas ações ficam bloqueadas;
- proteção contra caminhos ZIP inválidos e pacotes excessivamente grandes.

## Ativação por mundo
O gerenciador mantém:
- `world_behavior_packs.json`
- `world_resource_packs.json`

com UUID e versão do manifesto do pacote.

## Interface
Mantém a UI da v0.5.1:
- tipografia estilo Minecraft na interface;
- Console em monospace;
- responsividade;
- notificações;
- categorias do servidor;
- assinatura persistente.

Versão: 0.6.0
versionCode: 14
