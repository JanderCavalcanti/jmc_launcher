# JMCAR

Launcher para a central do carro (tbox Carlinkit CPC200, Android 13, tela 1280x720): mapa, música e dados do carro pelo OBD numa só tela, com painéis que você monta.

## Baixar

**[Última versão (releases)](https://github.com/JanderCavalcanti/jmc_launcher/releases/latest)**

Todas as versões, com as novidades de cada uma: [releases do JMCAR](https://github.com/JanderCavalcanti/jmc_launcher/releases).

Depois de instalado, o JMCAR procura sozinho as novas versões publicadas aqui e oferece a atualização na própria tela (Configurações > Sobre).

## O que tem

- Painéis com mapa, música, velocidade, clima e blocos do carro.
- Dados do carro por adaptador OBD Bluetooth (ELM327), códigos de falha e a tela "O que meu carro responde".
- Combustível Flex, Etanol e GNV, com os preços que você digita e a regra dos 70% para o etanol.
- Alertas de tempo do INMET para a cidade onde o carro está e para o destino.
- Configuração salva no Google Drive.

## Compilar

Android Studio com JDK 17 a 21, depois `./gradlew assembleGithubDebug`.

## Origem e licença

O JMCAR parte do Dashwheel, de Alexandre Leblanc, na última versão publicada sob a GNU GPL v3 (commit `fed7d976`, ver [ORIGEM.md](ORIGEM.md)).

Copyright (C) 2026 Alexandre Leblanc (deviloufr-ai), para o código original.
Modificações do JMCAR: Jander Cavalcanti.

Este programa é software livre: você pode redistribuí-lo e/ou modificá-lo sob os termos da GNU General Public License, versão 3 ou (a seu critério) qualquer versão posterior, publicada pela Free Software Foundation. É distribuído SEM NENHUMA GARANTIA; veja o texto completo em [LICENSE](LICENSE).
