# Câmeras de Casa (Android)

App nativo para Android que mostra e controla câmeras IP XiongMai (firmware "Sofia",
as que usam os apps XMEye e iCSee) direto pela rede local, sem depender da nuvem
do fabricante.

> É a versão para Android dos apps para Mac (`lan-cameras-mac`) e para iPhone
> (`lan-cameras-ios`), com os mesmos protocolos reescritos em Kotlin.

## O que faz

- Vídeo ao vivo das câmeras (H.264 e H.265, decodificados pelo hardware do celular) pelo RTSP.
- Câmeras com duas lentes empilhadas no mesmo quadro: mostra a lente móvel e, se quiser, a fixa.
- Movimento (PTZ) segurando as setas, zoom digital (pinça, toque duplo), posições salvas.
- Luz branca (automática, ligada, desligada) e rastreio automático.
- Ouvir a câmera, falar por ela ao vivo (segure o botão), tocar sons prontos e texto falado.
- Foto da lente atual, direto na galeria.
- Em retrato, o vídeo fica no topo e os controles rolam embaixo; em paisagem, o vídeo ocupa a
  tela inteira com os controles principais por cima.
- Senha ou endereço trocados: se o app do fabricante (iCSee, XMEye) trocar a senha de uma
  câmera, ou o roteador mudar o endereço dela, o app percebe quando ela recusa a senha guardada,
  pega os dados novos com a própria câmera e reabre só as conexões dela. O vídeo não fica
  tentando de novo com a senha recusada (as câmeras podem bloquear a conta depois de várias
  tentativas erradas).

O app só fica conectado às câmeras enquanto está na tela: em segundo plano ele solta as
sessões (as câmeras aguentam poucas ao mesmo tempo) e as reabre ao voltar.

## Estado

- **Núcleo (`nucleo/`)**: RTSP/RTP, depacotadores H.264 e H.265, ONVIF, DVRIP, WS-Discovery e
  varredura da rede, A-law, sons, fala com ritmo e armazenamento. Testado na JVM contra
  servidores falsos e conferido nas câmeras reais pela ferramenta de linha de comando.
- **App (`app/`)**: completo. Verificado num emulador com Android 17 ligado às câmeras reais
  (permissão de rede local, descoberta, vídeo das três câmeras, zoom, tela cheia, segundo plano
  e foto). Ainda não foi instalado num celular de verdade.

## Como funciona

Fala direto com cada câmera, sem servidor intermediário e sem abrir nenhuma porta
no celular:

| o quê | protocolo |
| --- | --- |
| vídeo e áudio | RTSP (porta 554), RTP por TCP |
| movimento e posições salvas | ONVIF PTZ (porta 8899) |
| luz, rastreio e fala | DVRIP (porta 34567) |
| descoberta | WS-Discovery (UDP multicast) e, se ninguém responder, varredura da porta 8899 |

O app pega as credenciais que a própria câmera entrega pelo ONVIF: ninguém digita
usuário nem senha.

## Compilar

Requer o JDK 17 ou mais novo e o SDK do Android com a plataforma 37 (Android 17). No Mac,
o JDK que vem com o Android Studio já serve.

```sh
./scripts/compilar.sh        # testes do núcleo, testes e lint do app, APK de depuração
./gradlew :nucleo:test       # só o núcleo (roda na JVM, sem Android)
```

### Instalar no celular

Uma vez só, crie a chave que assina o app (fica em `~/.config/lan-cameras/`, fora do
repositório; a senha é sorteada e nunca aparece):

```sh
./scripts/preparar_android_env.sh
```

Depois, com o celular ligado por USB e a depuração USB ativada:

```sh
./scripts/instalar_android.sh
```

### Testar o núcleo nas câmeras

`camspike` é uma ferramenta de linha de comando que usa o mesmo núcleo. Ela só lê: não
move, não muda a luz nem o rastreio e não toca som.

```sh
./gradlew -q :nucleo:run --args="descobrir --salvar"   # WS-Discovery e identificação
./gradlew -q :nucleo:run --args="varrer --salvar"      # sem multicast: varredura da rede
./gradlew -q :nucleo:run --args="estado"               # luz, rastreio, Wi-Fi e posições salvas
./gradlew -q :nucleo:run --args="video --segundos 5"   # quadros por segundo de cada câmera
./gradlew -q :nucleo:run --args="rtp --segundos 5"     # o que chega, camada por camada
```

As câmeras ficam em `~/.config/lan-cameras/camspike/` (ou em `CAMERAS_PASTA`). Usuário e
senha nunca são impressos.

### Acompanhar o app

O app registra o que faz com as câmeras (conexões, pausas em segundo plano), sem credenciais,
só quando a etiqueta de log está ligada:

```sh
adb shell setprop log.tag.Cameras DEBUG
adb logcat -s Cameras:D
```

Para abrir direto numa câmera (útil em testes): `adb shell am start -n com.lancameras.android/.MainActivity --es abrir Garagem`.

## Privacidade

As credenciais das câmeras ficam só no celular, cifradas com uma chave do Android Keystore,
e ficam fora do backup e da transferência para outro aparelho. Nada disso vai para log.
Este repositório não contém endereços, identificadores de aparelhos, credenciais nem
imagens de nenhuma instalação. Para conferir antes de publicar qualquer mudança:

```sh
./scripts/verificar-privacidade.sh
```

## Licença

MIT: código aberto, use, modifique e distribua à vontade. Veja [LICENSE](LICENSE).
