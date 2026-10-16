# Bubble BDS Host v0.7.0 — Gerenciador de versões BDS

Esta revisão adiciona um seletor de versão do Bedrock Dedicated Server ao cabeçalho da tela inicial.

## Novidades

- spinner no canto superior direito, ao lado da marca Bubble;
- consulta online da versão oficial e da prévia mais recente;
- fonte de dados: endpoint oficial Microsoft/Minecraft Services `api/v1.0/download/links`;
- aviso em forma de balão ancorado ao spinner por 20 segundos quando há atualização/prévia nova;
- modal com fundo escurecido;
- lista da versão oficial, prévia e versões já baixadas;
- download com progresso;
- alternância entre versões baixadas com o servidor parado;
- cache local dos ZIPs para permitir voltar para uma versão anterior;
- lixeira para apagar versões baixadas que não estejam em uso;
- preserva `server.properties`, permissões e allowlist durante a troca;
- mundos e add-ons não são apagados ao alternar a versão;
- a versão ativa não pode ser apagada.

## Segurança operacional

A troca de versão é bloqueada enquanto o servidor estiver ativo. O download pode ser feito normalmente e a versão pode ser ativada depois que o servidor for parado.

Versão do app: `0.7.0`
Version code: `15`
