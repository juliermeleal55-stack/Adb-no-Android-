# ADB no Android 📟

Um aplicativo Android **simples e organizado** para rodar comandos ADB **no próprio aparelho**, igual a um PC — **com suporte a ROOT**.

Sem PC, sem cabo, sem `adb` externo: você digita o comando (ou toca num comando pronto) e o resultado aparece na tela, num console estilo terminal.

---

## ✨ O que o app faz

- **Executa qualquer comando de shell** direto no aparelho (os mesmos comandos que você usa no `adb shell` do PC).
- **Funciona com ROOT**: os comandos rodam via `su` (compatível com **Magisk**, **KernelSU** e **SuperSU**).
- **Fallback sem root**: se não houver root (ou a permissão for negada), o app tenta pelo shell normal do aparelho — muitos comandos continuam funcionando (`getprop`, `pm list packages`, `df -h`, `uptime`…).
- **Indicador de ROOT** no topo: `ROOT ✓` (verde), `SEM ROOT` (vermelho) ou `VERIFICANDO…` (amarelo). Toque nele para re-verificar.
- **Comandos prontos organizados por categoria** (Dispositivo, Apps, Sistema, Rede, Arquivos, Ações) com um toque para rodar ou colar no campo.
- **Igual ao PC**: se você digitar `adb shell getprop …`, o app **remove o prefixo `adb shell`** sozinho e executa o resto.
- **Console de saída** com histórico, rolagem automática, botões **Limpar** e **Copiar tudo**.
- Tema escuro estilo terminal, interface mínima e direta.

## ️ Telas e uso

1. **Campo de comando** (cima): digite qualquer comando e toque em **Executar** (ou a tecla ↵).
2. **Comandos prontos** (meio): filtre pela categoria nos chips.
   - ▶ **verde** = roda o comando na hora.
   - ✏️ = copia o comando para o campo para você ajustar (ex.: trocar `<pacote>` pelo nome do app) e então executar.
3. **Saída** (baixo): o resultado de cada comando, com status:
   - `✓ Concluído (root)` — rodou com root.
   - `⚠ Concluído sem root` — rodou, mas sem privilégio de root.
   - `✗ Falha na execução` — erro (a mensagem aparece acima).
   - `⏱ Tempo esgotado (90 s)` — o comando levou tempo demais e foi cancelado.

### Exemplos de comandos

```
getprop ro.product.model          # modelo do aparelho
pm list packages -3               # apps que você instalou
pm uninstall com.seu.app          # desinstalar um app
pm install /sdcard/Download/x.apk # instalar um APK (com root)
df -h                             # armazenamento
ip addr                           # endereços IP
dumpsys battery                   # bateria
reboot                            # reiniciar (use com cuidado!)
```

> ⚠️ **Aviso**: com root, o app tem poder total sobre o sistema. Um comando errado
> (ex.: `rm -rf`, `reboot recovery`) pode danificar seu aparelho ou apagar dados.
> **Use por sua conta e risco.**

---

##  Como instalar / compilar

O projeto é Android nativo (Kotlin + Jetpack Compose). Duas formas de gerar o APK:

### Opção 1 — Android Studio (mais fácil)

1. Instale o [Android Studio](https://developer.android.com/studio) (versão **Koala** ou mais nova).
2. **File → Open** e escolha esta pasta do projeto.
3. Aguarde o sync do Gradle (ele baixa as dependências automaticamente).
4. Toque em ▶ **Run** com o aparelho conectado (USB debug) ou use
   **Build → Build Bundle(s)/APK(s) → Build APK(s)**.
5. O APK fica em `app/build/outputs/apk/debug/app-debug.apk`.

### Opção 2 — Linha de comando

Com JDK 17 e Android SDK instalados (variável `ANDROID_HOME` configurada):

```bash
./gradlew assembleDebug        # Linux/macOS
gradlew.bat assembleDebug      # Windows
```

APK gerado em `app/build/outputs/apk/debug/app-debug.apk`. Copie para o aparelho e instale.

### Requisitos do aparelho

- Android **7.0 (API 24)** ou mais novo.
- Para rodar com root: aparelho **rootado** (Magisk, KernelSU ou SuperSU).
  Na primeira execução o gerenciador de root vai perguntar a permissão — **aceite**.
- Nenhuma permissão especial é solicitada pelo app (os comandos rodam via `su`).

---

## 🛠️ Como funciona por baixo dos panos

- **Com root**: o app abre o `su` (shell interativo do root) e "digita" o comando no stdin — a mesma técnica usada por terminais de root. Isso funciona em qualquer implementação de `su` e evita problemas de aspas/escape. O código de saída é capturado com um marcador (`echo` no final do comando).
- **Sem root**: o app cai para `sh -c "<comando>"` como usuário `shell`.
- **Timeout**: comandos que travam são cancelados após **90 segundos**.
- A leitura da saída acontece em thread separada (evita travar com saídas grandes) e a UI roda em coroutines.

## 📁 Estrutura do projeto

```
app/src/main/java/com/adbnoandroid/
├── MainActivity.kt      # entrada do app
├── Theme.kt             # tema escuro estilo terminal
├── TerminalScreen.kt    # toda a interface (campo, categorias, console)
├── Commands.kt          # comandos prontos por categoria
└── RootShell.kt         # motor de execução (su / sh, timeout, exit code)
```

## 📝 Notas e limitações

- O app executa o comando **exatamente como digitado** — sem root, comandos que
  exigem privilégio (ex.: `mount`, arquivos de `/data`) vão falhar; o status
  indica quando rodou "sem root".
- Comandos interativos (`top` sem `-n`, `cat` sem arquivo) podem consumir o
  timeout de 90 s.
- Projeto educacional/ferramenta pessoal — contribuições são bem-vindas.
