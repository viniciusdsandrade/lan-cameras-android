# Câmeras de Casa (Android)

App nativo para Android que mostra e controla câmeras IP XiongMai (firmware "Sofia",
as que usam os apps XMEye e iCSee) direto pela rede local, sem depender da nuvem
do fabricante.

> É a versão para Android dos apps para Mac (`lan-cameras-mac`) e para iPhone
> (`lan-cameras-ios`), com os mesmos protocolos reescritos em Kotlin.

## Estado

- **Núcleo (`nucleo/`)**: pronto e testado nas câmeras. Inclui RTSP/RTP, os depacotadores
  H.264 e H.265, ONVIF (identificação, credenciais, movimento e posições salvas), DVRIP (luz,
  rastreio e fala), WS-Discovery e varredura da rede, A-law, sons e o armazenamento.
- **App (`app/`)**: em construção. Faltam o vídeo pelo `MediaCodec`, as telas, os controles e o som.

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
