# ADB no Android 📟

Um aplicativo Android **simples e organizado** que vira uma **platform-tools no seu bolso**: roda comandos ADB em **outros dispositivos por cabo USB (OTG) e Wi‑Fi** — com `adb devices` ao vivo, autenticação RSA igual ao PC, terminal de comandos, **logcat ao vivo com salvar/compartilhar**, **adb pull/push** e **sideload** — e também neste próprio aparelho **com ROOT**.

Sem PC: o app **implementa o protocolo ADB inteiro em Kotlin** (a mesma conversa de pacotes que o `adb` do PC faz com o `adbd` — por USB bulk ou TCP), conecta no aparelho de destino, autentica (diálogo “Permitir depuração”, como no PC) e roda comandos no shell dele.

---

## ✨ O que o app faz

### 🔌 USB (cabo/OTG) — o "adb devices" por cabo
- Detecta automaticamente aparelhos com depuração USB conectados (interface ADB: class 0xFF/0x42) — lista ao vivo, tipo `adb devices`.
- Permissão USB pedida via diálogo do sistema (botão **Permitir USB**).
- Conecta, autentica com chave RSA e abre o shell do aparelho conectado.

### 📶 Wi‑Fi (ADB sem fio)
- Adicione `IP:porta` do outro aparelho (ex.: `192.168.1.50:5555` ou a porta de “Depuração sem fio” do Android 11+).
- Status ao vivo: online / verificando / offline / aguardando permissão.

### 🖥️ Aba TERMINAL
- **Escolha o alvo** nos chips: 📱 *Este aparelho* (root/`su`, com fallback para shell normal) · 🔌 *USB* · 📶 *Rede*.
- Campo de comando + **Executar** (ou tecla ↵). Digite igual ao PC: `adb shell …` é aceito (o prefixo é removido).
- **Comandos prontos por categoria** (Dispositivo, Apps, **Logs**, Sistema, Rede, Arquivos, Ações): ▶ roda na hora, ✏️ copia para ajustar.
- **Console** com histórico, alvo de cada execução, `exit code`, **Limpar** e **Copiar tudo**.
- **✂ Cortar log**: em cada saída do console, **Cortar log** abre o texto num editor — segure no texto, arraste as alças e **copie/salve só o trecho** (sem seleção, usa tudo). Também há **Copiar tudo** e **Salvar .txt** por comando.

### 📝 Aba LOGS — coleta de logs igual ao PC
- **Logcat ao vivo**: stream do `logcat` do aparelho de destino (USB, Wi‑Fi ou este aparelho) em tempo real, com **Tudo / Só erros** + filtro extra (ex.: `Wifi:V`), botões **Parar**, **Limpar**, **💾 Salvar** e **📤 Compartilhar** (WhatsApp/e‑mail).
- **⬇ Baixar arquivo (`adb pull`)**: digite o caminho no aparelho (ex.: `/sdcard/Download/log.txt`) e baixe para a pasta de downloads do app.
- **⬆ Enviar arquivo (`adb push`)**: escolha um arquivo do gerenciador, defina o destino no aparelho (ex.: `/sdcard/Download/app.apk`) e envie com barra de progresso.
- O file-sync protocol (`RECV`/`SEND`/`STAT`/`DATA`/`DONE` sobre o serviço `sync:`) é o mesmo que o `adb` do PC usa.

### 📦 Aba SIDELOAD — o `adb sideload <zip>` do PC
- Envie um `.zip` (custom ROM, GApps…) para o **recovery** do aparelho de destino (USB ou Wi‑Fi), com **barra de progresso** e checksum — o protocolo file-sync (BEGIN/DATA/DONE) é o mesmo do adb.
- O destino precisa estar em RECOVERY com “Apply update from ADB”.

### 🔐 Autenticação (igual ao PC)
- O app gera e guarda uma **chave RSA 2048** (equivalente do `~/.android/adbkey`) no armazenamento privado.
- Na primeira conexão com cada aparelho (USB ou Wi‑Fi), o destino mostra **“Permitir depuração?”** — toque em **Permitir** uma vez; depois passa direto.

## ️ Como usar

### Por USB (cabo/OTG)
1. Conecte um cabo USB ao aparelho de destino (use adaptador OTG se precisar).
2. O destino precisa ter **Depuração USB** ativada.
3. No app, aba **Dispositivos** → seção “Via USB”: toque em **Permitir USB** (diálogo do sistema) → o aparelho aparece (como no `adb devices`) → ▶ abre o terminal.

### Por Wi‑Fi
1. Mesma rede Wi‑Fi nos dois aparelhos.
2. No destino: **Depuração sem fio** (Android 11+; use o IP:porta mostrados) ou `adb tcpip 5555` (mais antigos).
3. Aba **Dispositivos** → “Via rede” → adicione o IP → na primeira conexão, **aceite o diálogo no destino**.

### Coletar logs (aba Logs)
1. Escolha o **alvo** (USB/Wi‑Fi/este aparelho).
2. **▶ Iniciar logcat** → reproduza o problema no aparelho → o log aparece em tempo real.
3. **⏹ Parar** → **💾 Salvar** (gera um `.txt` na pasta do app) → **📤 Compartilhar** (envia por WhatsApp/e‑mail).
4. Arquivo que o destino gerou (ex.: `bugreport /sdcard/bugreport.zip` no Terminal)? Use **⬇ Baixar** com o caminho.

### Sideload
1. No destino: RECOVERY → **“Apply update from ADB”**.
2. Aba **Sideload**: escolha o dispositivo, escolha o `.zip` e toque em **Iniciar**.

> ⚠️ **Aviso**: com root (alvo local) ou acesso ADB (alvos USB/rede), os comandos têm poder total sobre o sistema. Um comando errado ou um `.zip` errado podem danificar o aparelho. **Use por sua conta e risco.**

---

## 🔨 Como instalar / compilar

Projeto Android nativo (Kotlin + Jetpack Compose).

### Opção 1 — Android Studio (mais fácil)
1. [Android Studio](https://developer.android.com/studio) (versão **Koala** ou mais nova, JDK 17 embutido).
2. **File → Open** → esta pasta → aguarde o sync.
3. ▶ **Run** (USB debug) ou **Build → Build Bundle(s)/APK(s) → Build APK(s)**.
4. APK em `app/build/outputs/apk/debug/app-debug.apk`.

### Opção 2 — Linha de comando (JDK 17 + Android SDK)
```bash
./gradlew assembleDebug        # Linux/macOS
gradlew.bat assembleDebug      # Windows
```

### Requisitos
- Android **7.0 (API 24)**+ para o app.
- USB Host (OTG) apenas para o modo por cabo — o app funciona sem ele (Wi‑Fi).
- Permissões do app: **INTERNET** e **USB Host** (via diálogo, quando precisar).

---

## 🛠️ Como funciona por baixo dos panos

- **Protocolo ADB em Kotlin** (`AdbProtocol.kt`): pacotes de 24 bytes little-endian (CNXN/AUTH/OPEN/WRTE/OKAY/CLSE), checksum por soma de bytes e magic XOR — o protocolo do AOSP, agnóstico do transporte.
- **Transportes** (`AdbTransport`):
  - `UsbTransport` — endpoints bulk da interface ADB via USB Host API (blocos de 4096 B, compatível com host controllers sensíveis a buffer grande);
  - `TcpTransport` — socket TCP no adbd (porta 5555 / depuração sem fio).
- **USB** (`AdbUsb.kt`): filtra dispositivos pela interface ADB (0xFF/0x42/0x01), pede permissão via `UsbManager`, claima a interface e abre os endpoints.
- **Autenticação** (`AdbAuth.kt`): assinatura `NONEwithRSA` (PKCS#1 v1.5, o que o `adbd` valida) do token de 32 bytes; chave pública em PEM para o diálogo “Permitir”.
- **Exit code**: o serviço `shell:` (v1) não devolve o código — o app executa `<cmd>; echo __marker__:$?` e lê o marcador (mesma técnica do modo root).
- **Sideload** (`AdbSideload.kt`): serviço `sideload:` + file-sync (`BEGIN:100644<8hex>\0` → `OKAY`, blocos de 4 KB → `DATA:<offset>`, `DONE:<checksum>\0` → `OKAY`), com progresso.
- UI em coroutines; execução em `Dispatchers.IO`; leitura de saída sem travar (threads/blocos).

## 📁 Estrutura do projeto

```
app/src/main/java/com/adbnoandroid/
├── MainActivity.kt      # entrada (inicializa AdbClient)
├── Theme.kt             # tema escuro estilo terminal
├── TerminalScreen.kt    # abas, alvos, execução e estado USB
├── TerminalTab.kt       # alvo + campo + comandos prontos + console
├── DevicesTab.kt        # "adb devices": USB ao vivo + rede
├── SideloadTab.kt       # adb sideload com progresso
├── Commands.kt          # comandos prontos por categoria
├── AdbProtocol.kt       # pacotes ADB + transportes (USB/TCP) + file-sync I/O
├── AdbUsb.kt            # detecção/permissão/open de USB + UsbTransport
├── AdbAuth.kt           # chave RSA 2048 (geração, persistência, assinatura)
├── AdbClient.kt         # alto nível: probe/shell (USB e Wi‑Fi)
├── AdbSideload.kt       # sideload (file-sync protocol)
├── AdbUtils.kt          # parsing do marcador de exit code
├── RootShell.kt         # execução local via su / sh
├── DevicesStore.kt      # persistência dos endereços de rede
└── UsbReceiver.kt       # plugue/permissão USB → UI
```

## 📝 Notas e limitações

- Cada comando abre uma conexão ADB nova (handshake rápido) — simples e confiável; a chave fica autorizada no destino após a primeira aprovação.
- O modo USB depende do USB Host do seu aparelho (na grande maioria, presente) e de um cabo/OTG que faça o papel de “host”.
- Na primeira conexão com cada aparelho, é **obrigatório aceitar o diálogo** de depuração nele (igual ao PC).
- Comandos interativos (`top` sem `-n`) podem estourar o timeout (60 s).
- Projeto educacional/ferramenta pessoal — contribuições são bem-vindas.
