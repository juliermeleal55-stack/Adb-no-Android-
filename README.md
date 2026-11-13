# ADB no Android 📟

Uma **platform-tools no bolso**: o app implementa o protocolo ADB **em Kotlin** e
controla outro aparelho Android por **cabo USB (OTG)** — sem PC,
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
| Arquivo | `app-debug.apk` · **~8,9 MB** |
| SHA-256 | veja o laudo do build mais recente (link abaixo) |
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

Uma tela só. De cima para baixo:

### 🔌 O aparelho
Mostra o que está no cabo: modelo, fabricante, versão do Android e em que modo
está (ADB normal, recovery, sideload ou fastboot). Se houver mais de um aparelho
plugado, aparece a lista para escolher. Se a depuração USB ainda não foi
autorizada no outro aparelho, o botão **Permitir acesso ao aparelho** abre o
diálogo do Android.

### ⌨️ O comando
Campo de texto e ▶. O que você digitar roda **no aparelho**, pelo protocolo ADB,
e a saída aparece embaixo linha por linha — **é o que o aparelho respondeu**, sem
resumo e sem invenção. Código de saída diferente de zero aparece em vermelho.

### ⚡ Os atalhos
Oito botões com o que se faz todo dia:

| Atalho | O que roda |
|---|---|
| **Instalar APK** | abre o seletor de arquivos e instala por streaming |
| **Espaço livre** | `df -h /data` |
| **Bateria** | `dumpsys battery` |
| **Tela** | `screencap -p` para `/sdcard` |
| **Reiniciar** | `reboot` (pede confirmação) |
| **Recovery** | `reboot recovery` (pede confirmação) |
| **Root** | `root` — só funciona em build userdebug/eng |
| **Remount** | `remount` — precisa de root |

Cada atalho mostra o comando que vai rodar, para você saber o que está pedindo.

### 🎨 Visual
Material You puro: no Android 12+ as cores vêm do seu papel de parede; abaixo
disso o app usa o esquema claro/escuro padrão. O botão ☀/☾ no topo inverte o
tema na hora. O console **não** segue o tema — saída de terminal precisa de
fundo próprio para erro ficar legível.

---

## 🔐 Como funciona a autenticação

O Android exige que o host prove que é confiável. O app gera um par de chaves
RSA no aparelho e, na primeira conexão, envia a chave pública no banner do
`CNXN`. O outro aparelho mostra o diálogo **“Permitir depuração USB?”** — ao
aceitar, ele guarda a chave e o app passa a ser aceito.

O formato da chave é o do AOSP, não o do Java: struct `RSAPublicKey` de **524
bytes** (modulus_size_words, n0inv, modulus[256], rr[256], exponent) em
little-endian, assinado com **SHA1withRSA** sobre o token de 32 bytes. Usar o
formato PEM/SPKI comum faz o `adbd` recusar — foi um dos bugs corrigidos aqui.

A chave fica no armazenamento privado do app. O diálogo do Android só aparece
uma vez por chave, então ele reaparece apenas se você desinstalar o app.

---

## 🚀 Como usar

1. No **outro** aparelho: Configurações → Opções do desenvolvedor →
   **Depuração USB** ligada.
2. Ligue os dois com um cabo **OTG** (o adaptador vai no aparelho que roda o
   app).
3. Abra o app: ele lista o aparelho. Toque em **Permitir acesso** se pedir.
4. Aceite o diálogo **“Permitir depuração USB?”** na tela do outro aparelho.
5. Digite um comando, ou use um atalho.

> Sem cabo OTG, nada acontece: **este app é só por cabo**, de propósito.

---

## 🔨 Compilar

### Android Studio
1. **File → Open** na pasta do projeto.
2. Deixe o Gradle sincronizar (baixa AGP 8.5.2, Kotlin 1.9.24, Compose BOM
   2024.06.00).
3. **Run**, ou **Build → Build Bundle(s)/APK(s) → Build APK(s)**.

### Linha de comando
```bash
# JDK 17, Android SDK 34, build-tools 34.0.0
./gradlew assembleDebug
# saída: app/build/outputs/apk/debug/app-debug.apk
```

### Pelo GitHub Actions (sem ter nada instalado)
Cada push dispara o workflow `.github/workflows/build-apk.yml`, que:
1. instala JDK 17 e o Android SDK 34;
2. roda `./gradlew assembleDebug`;
3. publica o APK no **Release** (`v2.0`, substituído a cada build);
4. audita o que gerou (badging, assinatura, dependências no DEX) e publica o
   laudo na branch `ci-report`;
5. **se falhar**, empurra o log inteiro para a branch `ci-build-log`.

O item 4 existe porque o APK não pode ser baixado de dentro do ambiente de
desenvolvimento — é o runner medindo o próprio resultado. O item 5 existe porque
os logs do CI também não são acessíveis de lá.

### Requisitos
- JDK 17 · Android SDK 34 · Gradle 8.7 (o wrapper baixa)
- Kotlin 1.9.24 · AGP 8.5.2 · Compose Compiler 1.5.14
- **minSdk 24** (Android 7.0) · targetSdk 34
- Permissões: **USB Host** (pedida em tempo de execução)
- `minSdk` é **24**, não 23: foi o mínimo em que o projeto compilou com esta
  combinação de bibliotecas.

---

## 🛠️ Como funciona por dentro

O app é o **host** ADB: implementa o protocolo sobre USB, no lugar do
`platform-tools` do computador.

```
USB (OTG) → AdbUsb (claim da interface, leitura/escrita de pacotes)
          → AdbProtocol (CNXN, AUTH, OPEN, OKAY, WRTE, CLSE; shell v2)
          → AdbShell / AdbSync / AdbSideload / FastbootUsb
          → AdbClient (fachada de alto nível, com um lock por aparelho)
          → HomeScreen (Compose)
```

Pontos que exigiram cuidado e estão documentados no código:

- **IDs de pacote**: `arg0` é o id local do remetente, `arg1` é o do par. O
  aparelho descarta `WRTE`/`OKAY`/`CLSE` com `arg0 == 0`.
- **Ack**: sem *delayed ack*, cada `WRTE` precisa de resposta — exceto os dados
  de um push (`SEND`), que não são respondidos em `DATA`.
- **Sync**: só `DATA`/`FAIL`/`OKAY` usam a moldura `id + length`; `STAT` e
  `DENT` mandam a struct empacotada direto, e a listagem termina com um `DONE`
  do tamanho da struct (20 ou 76 bytes).
- **USB**: transferências de até 16 KiB alinhadas a `wMaxPacketSize`, com ZLP
  quando o tamanho é múltiplo do pacote.
- **Concorrência**: `Semaphore(1)` por aparelho — a interface USB é exclusiva,
  e uma segunda operação recebe "device busy" se abrir o aparelho no meio da
  outra.

---

## 📁 Estrutura

```
app/src/main/java/com/adbnoandroid/
  MainActivity.kt      activity + estado do app
  ui/HomeScreen.kt     a tela única (aparelho, comando, atalhos)
  ui/Components.kt     Confirmação de ação destrutiva + cores de estado
  ui/Theme.kt          Material You (dinâmico) + paleta do console
  ui/FilePickers.kt    nome e cópia do arquivo escolhido no seletor
  AdbClient.kt         fachada: shell, push/pull, install, reboot, root…
  AdbProtocol.kt       pacotes ADB, handshake, shell v2, exceções
  AdbUsb.kt            transporte USB (interface, endpoints, permissão)
  AdbAuth.kt           geração de chave e assinatura no formato do AOSP
  AdbShell.kt          execução de comandos + streaming
  AdbSync.kt           push/pull/list/stat
  AdbSideload.kt       envio de OTA para o recovery
  FastbootProtocol.kt  protocolo fastboot
  FastbootUsb.kt       transporte fastboot
  AppState.kt          estado observável (aparelhos, seleção, snapshots)
  UsbReceiver.kt       eventos de plug/remoção/permissão
  FileSaver.kt         salvar arquivos baixados
```

---

## 📝 Notas e limitações

- **É um APK de debug** assinado com a chave de debug do Android.
- **Não foi testado em aparelho real.** Compila e empacota (verificado no CI),
  mas o comportamento USB/OTG só se prova com dois aparelhos na mão.
- `root`, `remount` e `unroot` **só** funcionam em builds `userdebug`/`eng`.
  Em build de produção o `adbd` recusa, e o app mostra a recusa do aparelho —
  não finge sucesso.
- O app **não** faz root. Ele fala com um `adbd` que já esteja rodando.
- Sem Wi‑Fi de propósito: só cabo. `adb tcpip` e conexão sem fio foram removidos
  a pedido, junto com os presets e a tela correspondente.
- O histórico de comandos vive na memória da tela; fechar o app limpa.
