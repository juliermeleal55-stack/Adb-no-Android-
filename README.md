# ADB no Android 📟

Uma **platform-tools no bolso**: o app implementa o protocolo ADB **em Kotlin** e
controla outros aparelhos Android por **cabo USB (OTG)** ou **Wi‑Fi** — sem PC,
sem root no aparelho que controla.

É a mesma conversa de pacotes que o `adb` do computador faz com o `adbd`: abre a
interface USB do aparelho, autentica com chave RSA (o conhecido diálogo
“Permitir depuração USB?”) e roda comandos reais, transfere arquivos de verdade.

---

## ⬇️ Baixar o APK

**[📦 Baixar o APK (v2.0)](https://github.com/juliermeleal55-stack/Adb-no-Android-/releases/latest)**

O APK é compilado pelo GitHub Actions a cada push — não é um arquivo que alguém
subiu à mão. O que o build produziu, medido no próprio runner:

| Item | Valor |
|---|---|
| Arquivo | `app-debug.apk` · **9,2 MB** (9.167.316 bytes) |
| SHA-256 | `eba274a1561b971244f78cd1700d72294772230d6d36f009db3b62e0e104b362` |
| Pacote | `com.adbnoandroid.debug` |
| Versão | 2.0 (versionCode 2) |
| minSdk / targetSdk | **24** (Android 7.0) / 34 |
| Assinatura | `CN=Android Debug` — assinado, instala |
| DEX | 4 arquivos · 12.968 classes |

> ⚠️ **É um APK de debug**, assinado com a chave de debug do Android. Serve para
> instalar e testar; para publicar na Play Store é preciso gerar um build de
> release com sua própria keystore. O `minSdk` é **24**, não 23.

O laudo completo de cada build (badging, assinatura, dependências empacotadas no
DEX) fica na branch [`ci-report`](https://github.com/juliermeleal55-stack/Adb-no-Android-/tree/ci-report)
em `.ci-logs/APK.md`; o log de build fica em `ci-build-log` quando falha.

**Instalar:** baixe no celular → abra o arquivo → permita "instalar de fontes
desconhecidas" para o navegador/gerenciador de arquivos. Por cabo: ative a
depuração USB no **outro** aparelho e conecte o cabo OTG.

---

## ✨ O que o app faz

### 🔌 Dispositivos (`adb devices`)
- Detecta aparelhos com depuração USB ligada (interface vendor-specific
  0xFF/0x42/0x01) e lista **ao vivo**, inclusive vários ao mesmo tempo.
- Wi‑Fi: adicione `IP:porta` (ou use a porta da “Depuração sem fio”).
- Mostra **modelo, fabricante, versão do Android, SDK, build, serial, slot A/B**
  e o estado real de cada um: online · aguardando autorização · recovery ·
  sideload · fastboot · offline.
- **Fastboot**: a lista separa os aparelhos em modo bootloader, que usam as
  ferramentas Fastboot.

### ⌨️ Terminal
- Escolha o aparelho alvo e digite qualquer comando — execução **real**, sem
  simulação e sem “comandos de mentira”.
- Saída em tempo real (shell v2), cores por tipo, `exit code` verdadeiro,
  histórico com ↑/↓, copiar, limpar e exportar `.txt`.
- Comandos prontos por categoria, com o cuidado de só aparecer o que faz
  sentido no modo atual do aparelho.

### 📁 Arquivos (`adb push` / `adb pull`)
- Navegação de diretórios no aparelho, criação de pasta, apagar, copiar, mover.
- Envio e download com **barra de progresso, velocidade e tamanho**.
- Pasta inteira também desce (o app empacota com `tar` no aparelho, baixa e
  extrai localmente — é o que o `adb pull <pasta>` faz).
- Arquivos baixados podem ser publicados na pasta **Download** do celular e
  compartilhados por qualquer app.

### 📋 Logs e diagnóstico
- **Logcat ao vivo** com filtros por prioridade, tag e processo; parar, limpar,
  salvar `.txt` e compartilhar.
- Coleta pontual: `dmesg`, `kmsg`, `dumpsys`, propriedades, partições, memória,
  kernel/boot, hardware.
- **Bugreport** completo, gerado e baixado do aparelho.

### 🧰 Ferramentas
- Reiniciar (normal, recovery, bootloader, sideload).
- `adb root`, `unroot`, `remount`, `tcpip`, ler IP do Wi‑Fi, reconectar.
- Instalar APK (com `-r`, `-g`, `-d`), desinstalar, listar pacotes.
- **Backup** de app via Backup Manager (com a saída real do aparelho).

### 📦 Sideload e Fastboot
- Sideload de `.zip` (ROM/OTA) no recovery, com progresso e detecção de falha.
- Fastboot: `devices`, `getvar`, `reboot`, `flash`, `boot`, `erase`.
- **Partições críticas** (`bootloader`, `abl`, `xbl`, `modem`, `efs`, `super`…)
  não podem ser apagadas pelo app: isso inutilizaria o aparelho sem
  possibilidade de recuperação.

### 🕘 Histórico
- Toda operação fica registrada com aparelho, resultado e horário.

---

## 🔐 Como funciona a autenticação

No primeiro contato com cada aparelho, o `adbd` envia um token e exige uma
assinatura RSA. O app:

1. gera (uma vez) uma chave RSA 2048 e guarda no armazenamento privado;
2. assina o token com **SHA‑1 + PKCS#1 v1.5** — exatamente o que o `adbd`
   valida (`RSA_verify(NID_sha1, …)`);
3. se a chave ainda não é conhecida, envia a **chave pública no formato binário
   do Android** (`android_pubkey`), e o aparelho mostra o diálogo de permissão.

Depois de tocar em **Permitir** uma vez, as conexões seguintes passam direto.

---

## 🚀 Como usar

### Por USB (cabo/OTG)
1. Ligue a **Depuração USB** no aparelho de destino.
2. Conecte o cabo (com adaptador OTG, se necessário).
3. Aba **Dispositivos** → toque em **Permitir USB** → aceite o diálogo no
   aparelho de destino.

### Por Wi‑Fi
1. Mesma rede nos dois aparelhos.
2. No destino: **Depuração sem fio** (Android 11+) ou `adb tcpip 5555`.
3. **Dispositivos** → adicionar `IP:porta` → aceitar o diálogo no destino.

### Sideload
1. No destino: recovery → **Apply update from ADB**.
2. Aba **Ferramentas** → Sideload → escolher o `.zip` → **Iniciar**.

> ⚠️ **Aviso**: com acesso ADB o poder sobre o aparelho é total. Um comando
> errado ou um `.zip` incompatível pode danificar o aparelho. Use por sua conta
> e risco — o app sempre mostra **qual aparelho** será afetado e pede
> confirmação nas operações destrutivas.

---

## 🔨 Compilar

Projeto Android nativo: Kotlin + Jetpack Compose (Material 3).

### Android Studio
1. Android Studio **Koala** ou mais novo (JDK 17).
2. **File → Open** → esta pasta.
3. **Run**, ou **Build → Build Bundle(s)/APK(s) → Build APK(s)**.

### Linha de comando (JDK 17 + Android SDK)
```bash
./gradlew assembleDebug
```

### Requisitos
- Android **7.0 (API 24)** ou superior.
- USB Host (OTG) só é necessário para o modo por cabo.
- Permissões: **INTERNET** (Wi‑Fi) e **USB Host** (pedida em tempo de execução).

---

## 🛠️ Como funciona por dentro

- **Protocolo ADB** (`AdbProtocol.kt`): pacotes de 24 bytes little-endian
  (CNXN/AUTH/OPEN/WRTE/OKAY/CLSE), payload negociado até 256 KB, protocolo
  v2 (checksum dispensado). Cada serviço tem seu **id local e remoto** — é o par
  que o `adbd` usa para rotear os pacotes.
- **Transportes**: `UsbTransport` (bulk, transferências de até 16 KB para não
  estourar a alocação contígua do kernel, com ZLP quando o tamanho é múltiplo do
  pacote) e `TcpTransport` (Wi‑Fi).
- **Shell** (`AdbShell.kt`): v2 com frames próprios (stdout/stderr/exit/stdin),
  reproduzindo o `shell_protocol.h`; v1 com marcador para descobrir o `exit
  code`.
- **Transferência** (`AdbSync.kt`): file-sync real — `STAT`/`LIST`/`RECV`/`SEND`
  com as structs `sync_stat_v1/v2` e `sync_dent_v1/v2` exatamente como o AOSP
  as define.
- **Sideload** (`AdbSideload.kt`): `sideload-host:<tamanho>:65536`, o recovery
  pede bloco a bloco e o app responde com os bytes do arquivo.
- **Fastboot** (`FastbootProtocol.kt`): comandos ASCII de até 4 KB, respostas
  `OKAY`/`FAIL`/`DATA`/`INFO`, com `download:%08x` antes de gravar.
- **Concorrência**: cada aparelho tem um lock — o Android recusa duas
  `claimInterface` simultâneas, então as operações esperam a vez em vez de
  falhar.

## 📁 Estrutura

```
app/src/main/java/com/adbnoandroid/
├── MainActivity.kt       # entrada da UI (Compose)
├── AppState.kt           # estado: dispositivos, seleção, status, histórico
├── AdbProtocol.kt        # pacotes ADB + transportes (USB/TCP)
├── AdbUsb.kt             # detecção/permissão da interface ADB + UsbTransport
├── AdbAuth.kt            # chave RSA 2048 (formato AndroidPubKey)
├── AdbShell.kt           # shell v1/v2 com saída em tempo real
├── AdbSync.kt            # push/pull/list/stat (file-sync)
├── AdbSideload.kt        # sideload (sideload-host + legado)
├── AdbClient.kt          # API de alto nível usada pela UI
├── FastbootProtocol.kt   # constantes e regras do fastboot
├── FastbootUsb.kt        # transporte fastboot + sessão
├── FileSaver.kt          # salvar/compartilhar/abrir arquivos
├── DevicesStore.kt       # endereços de rede salvos
├── Commands.kt           # comandos prontos por categoria
├── UsbReceiver.kt        # eventos USB → UI
└── ui/                   # Material 3
    ├── Theme.kt, Components.kt, AppScaffold.kt
    ├── DevicesScreen.kt, TerminalScreen.kt, FilesScreen.kt
    └── ToolsScreen.kt, LogsScreen.kt, FastbootScreen.kt, HistoryScreen.kt
```

## 📝 Notas e limitações

- A chave fica autorizada no aparelho após a primeira aprovação; revogar em
  **Opções do desenvolvedor → Revogar autorizações** faz o diálogo aparecer de
  novo.
- O modo USB depende do USB Host do celular que controla (presente na maioria)
  e de um cabo/adaptador OTG que possa atuar como host.
- Comandos interativos (`top` sem `-n`) podem estourar o timeout.
- `adb backup` clássico foi desativado no Android 12+; o app usa o Backup
  Manager e mostra a resposta real do aparelho.
- `kill-server`/`start-server` não existem no contexto do app (não há daemon
  local); o equivalente útil é **Reconectar**.
- Projeto pessoal/educacional — contribuições são bem-vindas.
