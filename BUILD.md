# Como compilar

Guia curto para o primeiro build. Se der erro, veja o fim do arquivo.

## Pelo Android Studio (recomendado)

1. **File → Open** e escolha a pasta do projeto.
2. Espere o *Gradle Sync* terminar (ele baixa as dependências; a primeira vez
   demora alguns minutos).
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. O APK sai em `app/build/outputs/apk/debug/app-debug.apk`.

Para rodar direto no celular: ligue a depuração USB e clique em ▶ **Run**.

## Pela linha de comando

```bash
./gradlew assembleDebug
```

(no Windows: `gradlew.bat assembleDebug`)

## O que o build precisa

| Item | Versão |
|---|---|
| JDK | 17 |
| Gradle | 8.7 (o wrapper baixa sozinho) |
| Android SDK | plataforma 34 (o Studio baixa se faltar) |
| Conexão | internet no primeiro build (dependências) |

## Se der erro

**Cole aqui no chat a parte do erro.** O que ajuda mais:

- No Android Studio: a aba **Build** (a barra inferior). Clique com o botão
  direito → *Copy all* e cole.
- Ou o final do texto do `./gradlew assembleDebug` — as últimas ~40 linhas,
  que é onde ficam os `e: arquivo.kt:linha:coluna`.

Não precisa recortar: erro grande é melhor que erro pela metade.

### Os erros mais prováveis (e o que significam)

| Mensagem | Causa |
|---|---|
| `Unresolved reference: X` | Falta um `import` ou um nome mudou. Fácil de corrigir. |
| `Cannot access 'X': it is private` | Alguma função ficou privada e a UI chama. |
| `Type mismatch` | Um parâmetro com tipo diferente do esperado. |
| `None of the following functions can be called` | Nome/quantidade de argumentos errada. |
| `@Composable invocations can only happen from...` | Uma tela chamando outra fora do contexto certo. |
| `This declaration needs opt-in` | Falta `@OptIn` numa API experimental. |
| `Unresolved reference: R` | Erro anterior impede gerar o `R`; corrija os de cima primeiro. |
| `Plugin [id: 'com.android.application'] was not found` | Sem internet no primeiro build, ou versão de Gradle incompatível. |
| `SDK location not found` | Android Studio não configurou o SDK; `File → Sync Project`. |

> Importante: se aparecer `Unresolved reference: R`, **ignore** — ele é
> consequência de outro erro. Corrija o primeiro erro da lista e ele some.
