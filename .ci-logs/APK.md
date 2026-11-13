# Laudo do APK gerado

run: 43498798469
commit: ce00851e162cd6f0ef36d8b070d061cb3a1dfbca

## Arquivo
```
-rw-r--r-- 1 runner runner 8969170 Nov 13 20:11 app/build/outputs/apk/debug/app-debug.apk
```

## SHA-256
```
5191a3fd1e9d375418d74bbe717401140d7a755fa837fda0f512e2bf68a3212a  app/build/outputs/apk/debug/app-debug.apk
```

## Badging (pacote, versoes, SDKs)
```
package: name='com.adbnoandroid.debug' versionCode='2' versionName='2.0-debug' platformBuildVersionName='14' platformBuildVersionCode='34' compileSdkVersion='34' compileSdkVersionCodename='14'
sdkVersion:'24'
targetSdkVersion:'34'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.FOREGROUND_SERVICE'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
uses-permission: name='android.permission.ACCESS_WIFI_STATE'
uses-permission: name='com.adbnoandroid.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
application-label:'ADB no Android'
application-label-af:'ADB no Android'
application-label-am:'ADB no Android'
application-label-ar:'ADB no Android'
application-label-as:'ADB no Android'
application-label-az:'ADB no Android'
application-label-be:'ADB no Android'
application-label-bg:'ADB no Android'
application-label-bn:'ADB no Android'
application-label-bs:'ADB no Android'
application-label-ca:'ADB no Android'
application-label-cs:'ADB no Android'
application-label-da:'ADB no Android'
application-label-de:'ADB no Android'
application-label-el:'ADB no Android'
application-label-en-AU:'ADB no Android'
application-label-en-CA:'ADB no Android'
application-label-en-GB:'ADB no Android'
application-label-en-IN:'ADB no Android'
application-label-en-XC:'ADB no Android'
application-label-es:'ADB no Android'
application-label-es-US:'ADB no Android'
application-label-et:'ADB no Android'
application-label-eu:'ADB no Android'
application-label-fa:'ADB no Android'
application-label-fi:'ADB no Android'
application-label-fr:'ADB no Android'
application-label-fr-CA:'ADB no Android'
application-label-gl:'ADB no Android'
application-label-gu:'ADB no Android'
application-label-hi:'ADB no Android'
application-label-hr:'ADB no Android'
```

## Assinatura
```
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: 1c317600b9f30bdac37a99a3ecffc11f44b745832025d6cbf0b8efb05591a06b
Signer #1 certificate SHA-1 digest: 90719442f415b0c9883816c94589572b2385a956
Signer #1 certificate MD5 digest: 0d28dcbcfc536b1d3ee6d1264b09a032
```

## Conteudo essencial
```
 17301536  1981-01-01 01:01   classes.dex
    33228  1981-01-01 01:01   classes2.dex
   286324  1981-01-01 01:01   classes3.dex
   338628  1981-01-01 01:01   classes4.dex
  4521776  1981-01-01 01:01   classes5.dex
     8160  1981-01-01 01:01   AndroidManifest.xml
   411036  1981-01-01 01:01   resources.arsc
```

## Classes do app presentes no DEX
```
classes.dex: 0 referencias a com/adbnoandroid
classes2.dex: 8 referencias a com/adbnoandroid
classes3.dex: 232 referencias a com/adbnoandroid
classes4.dex: 322 referencias a com/adbnoandroid
classes5.dex: 0 referencias a com/adbnoandroid
```

## Teste decisivo: dependencias empacotadas no APK

Busca por SUBSTRING no binario do DEX (grep -a). O teste anterior
usava strings|grep -x, que exige a linha inteira e da falso
negativo — o DEX nao separa descritores em linhas.
```
tamanho do DEX concatenado: 22481492 bytes

PADRAO                                         OCORRENCIAS
com/adbnoandroid/MainActivity                  47       OK
com/adbnoandroid/AdbClient                     76       OK
androidx/compose/runtime/Composer              1206     OK
androidx/compose/ui/platform/AndroidComposeView 169      OK
androidx/compose/material3                     4614     OK
androidx/activity/ComponentActivity            51       OK
kotlinx/coroutines                             3140     OK
androidx/core/app/NotificationCompat           168      OK
androidx/lifecycle                             681      OK
```

## Cabecalho de cada DEX (dexdump do build-tools)
```
classes.dex: string_ids_size : 85267 class_defs_size : 9570 
classes2.dex: string_ids_size : 420 class_defs_size : 83 
classes3.dex: string_ids_size : 2764 class_defs_size : 153 
classes4.dex: string_ids_size : 2271 class_defs_size : 184 
classes5.dex: string_ids_size : 28923 class_defs_size : 2642 
```

## Avisos do compilador
```
w: file:///home/runner/work/Adb-no-Android-/Adb-no-Android-/app/src/main/java/com/adbnoandroid/AdbClient.kt:880:28 Variable 'remoteId' initializer is redundant
w: file:///home/runner/work/Adb-no-Android-/Adb-no-Android-/app/src/main/java/com/adbnoandroid/AdbProtocol.kt:324:17 The value 'true' assigned to 'var sentPublicKey: Boolean defined in com.adbnoandroid.AdbConnection.handshake' is never used
w: file:///home/runner/work/Adb-no-Android-/Adb-no-Android-/app/src/main/java/com/adbnoandroid/AdbSync.kt:440:21 Variable 'gotAny' is assigned but never accessed
w: file:///home/runner/work/Adb-no-Android-/Adb-no-Android-/app/src/main/java/com/adbnoandroid/AdbSync.kt:444:29 The value 'true' assigned to 'var gotAny: Boolean defined in com.adbnoandroid.AdbFiles.listEntries.`<anonymous>`' is never used
```
