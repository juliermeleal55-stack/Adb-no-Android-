# ADB no Android 📟

Um aplicativo Android **simples e organizado** para rodar comandos ADB **em outros dispositivos pela rede** — igual ao `adb` de um PC — e também **neste aparelho com ROOT**.

Sem PC, sem cabo: o app **implementa o protocolo ADB dentro do próprio app** (a mesma conversa de pacotes que o `adb` do PC faz com o `adbd`), conecta no aparelho de destino por Wi‑Fi, autentica com chave RSA (como o PC pede para aceitar a depuração) e roda comandos no shell dele — ou executa localmente via `su` (root).

---

## ✨ O que o app faz

### ABA TERMINAL
- **Escolha o alvo** nos chips do topo:
  - 📱 **Este aparelho** — os comandos rodam localmente via `su` (Magisk/KernelSU/SuperSU), com fallback para o shell normal se não houver root.
  - **Dispositivos remotos** — os comandos rodam no shell do outro aparelho, via ADB sem fio.
- **Campo de comando** + botão **Executar** (ou tecla ↵). Digite igual ao PC: `adb shell …` é aceito — o prefixo é removido sozinho.
- **Comandos prontos por categoria** (Dispositivo, Apps, Sistema, Rede, Arquivos, Ações):
  - ▶ roda na hora · ✏️ copia para o campo para ajustar (ex.: `pm uninstall <pacote>`).
- **Console de saída** com histórico, rolagem automática, `exit code`, botões **Limpar** e **Copiar tudo**, e o alvo de cada execução.

### ABA DISPOSITIVOS
- **Adicionar aparelho** por `IP:porta` (ex.: `192.168.1.50:5555`).
- Lista com **status ao vivo**: online / verificando / offline / aguardando permissão.
- Botões: **Testar** conexão, **Abrir terminal** desse dispositivo, **Remover**.
- **Guia de conexão** passo a passo (como ativar o ADB sem fio no aparelho de destino).

### AUTENTICAÇÃO (igual ao PC)
- O app gera e guarda uma **chave RSA 2048** (equivalente do `~/.android/adbkey` do PC) no armazenamento privado.
- Na **primeira** conexão, o aparelho de destino mostra o diálogo **“Permitir depuração?”** — toque em **Permitir** uma vez; depois a conexão passa direto.
- Com ROOT no aparelho local, os comandos locais rodam como `uid=0`.

## ️ Como conectar em outro aparelho

1. **Mesma rede Wi‑Fi** nos dois aparelhos.
2. No **aparelho de destino**, ative o ADB sem fio:
   - **Android 11 ou mais novo**: *Configurações → Opções do desenvolvedor → Depuração sem fio* — use o **IP:porta** mostrados ali (ex.: `192.168.1.50:37245`).
   - **Android 10 ou mais antigo**: conecte por USB uma vez e execute `adb tcpip 5555` (fica na porta `5555`).
3. No app, aba **Dispositivos** → adicione o endereço → na primeira conexão, **aceite o diálogo no aparelho de destino**.
4. Toque em ▶ e mande comandos: `getprop`, `pm list packages`, `df -h`, `reboot`…

> ⚠️ **Aviso**: com root (alvo local) ou com acesso ADB (alvos remotos), os comandos têm poder total sobre o sistema. Um comando errado pode danificar o aparelho ou apagar dados. **Use por sua conta e risco.**

---

## 🔨 Como instalar / compilar

Projeto Android nativo (Kotlin + Jetpack Compose). Duas formas de gerar o APK:

### Opção 1 — Android Studio (mais fácil)
1. Instale o [Android Studio](https://developer.android.com/studio) (versão **Koala** ou mais nova, JDK 17 embutido).
2. **File → Open** → escolha esta pasta → aguarde o sync do Gradle.
3. ▶ **Run** (aparelho com USB debug) ou **Build → Build Bundle(s)/APK(s) → Build APK(s)**.
4. APK em `app/build/outputs/apk/debug/app-debug.apk`.

### Opção 2 — Linha de comando
Com JDK 17 e Android SDK (`ANDROID_HOME`):
```bash
./gradlew assembleDebug        # Linux/macOS
gradlew.bat assembleDebug      # Windows
```

### Requisitos
- Android **7.0 (API 24)**+ para o app.
- Para o **alvo remoto**: o `adbd` dele acessível pela rede (Depuração sem fio ou `adb tcpip 5555`).
- Para o **alvo local com root**: Magisk, KernelSU ou SuperSU (aceite a permissão quando aparecer).
- Permissões do app: apenas **INTERNET** (para falar com os outros aparelhos).

---

## 🛠️ Como funciona por baixo dos panos

- **ADB sem fio em Kotlin** (`AdbProtocol.kt`): conexão TCP direta no `adbd` do destino, banner `CNXN`, autenticação `AUTH` (token → assinatura `NONEwithRSA` → chave pública PEM), serviço `OPEN:shell:<comando>` e leitura `WRTE`/`OKAY`/`CLSE` com checksum — o protocolo é o mesmo do AOSP.
- **Exit code**: como o serviço `shell:` (v1) não devolve o código, o app executa `<comando>; echo __marker__:$?` e lê o marcador — mesma técnica dos terminais de root.
- **Modo local** (`RootShell.kt`): abre o `su` (shell interativo) e “digita” o comando no stdin (compatível com Magisk/KernelSU/SuperSU); sem root, cai para `sh -c`.
- **Timeouts**: conexão 5 s; comando remoto 60 s; comando local 90 s.
- UI em coroutines; leitura de saída em threads separadas (evita travar com saídas grandes).

## 📁 Estrutura do projeto

```
app/src/main/java/com/adbnoandroid/
├── MainActivity.kt      # entrada do app (inicializa AdbClient)
├── Theme.kt             # tema escuro estilo terminal
├── TerminalScreen.kt    # abas, estado, execução e dispositivos
├── TerminalTab.kt       # alvo + campo + comandos prontos + console
├── DevicesTab.kt        # adicionar/testar/remover dispositivos
├── Commands.kt          # comandos prontos por categoria
├── AdbProtocol.kt       # protocolo ADB (CNXN/AUTH/OPEN/WRTE/CLSE)
├── AdbAuth.kt           # chave RSA 2048 (geração, persistência, assinatura)
├── AdbClient.kt         # alto nível: probe(), shell(), devices
├── AdbUtils.kt          # parsing do marcador de exit code
├── RootShell.kt         # execução local via su / sh
└── DevicesStore.kt      # persistência da lista de dispositivos
```

## 📝 Notas e limitações

- O app fala com o `adbd` **direto** (sem servidor ADB local): um novo “handshake” rápido por comando — simples e confiável.
- Na primeira conexão com cada aparelho, é **obrigatório aceitar o diálogo** de depuração nele (igual ao PC).
- Comandos interativos (`top` sem `-n`) podem estourar o timeout.
- Projeto educacional/ferramenta pessoal — contribuições são bem-vindas.
