# Agents Mod (Fabric 1.21.11)

30 agentes, 120 habilidades (C, Q, E, X) com efeitos 3D reais, desbloqueadas por crafting no survival.

- **Crafting:** cada habilidade tem um item de desbloqueio (receita sem forma, veja a receita no loadout ou no livro de receitas).
  Segure o item e clique com o botão direito UMA vez: a habilidade fica permanente (mesmo depois de morrer).
- **Usar:** equipe um agente (tecla **V**, loadout) e use **C / Q / E / X**. O primeiro desbloqueio já equipa o agente.
- **HUD:** coluna vertical no lado direito com as 4 habilidades, ícone, tecla e recarga.
- **Loadout (V):** grade dos agentes, detalhes de cada habilidade (descrição, recarga, receita) e botão Equipar. Duplo clique equipa.
- **Modo de tiro** (Blade Storm, Hunter's Fury, Showstopper, Tour de Force, Overdrive): depois de ativar, clique esquerdo dispara.

## Editar/Adicionar agentes
Tudo vem da tabela `AGENTS` em `tools/generate_assets.py`. Edite e rode `python3 tools/generate_assets.py`
(regenera agents.json, modelos 3D, ícones, receitas e textos pt_br/en_us). O Java lê o `agents.json`.

## Compilar
GitHub Actions (`.github/workflows/build.yml`) gera o jar em Artifacts. Local: `gradle build` (Java 21).
