package com.lancameras.nucleo

/**
 * Mascara credenciais em textos que podem ir para log, tela ou arquivo.
 *
 * A URL RTSP das câmeras XiongMai leva o usuário e o hash da senha no próprio caminho, e o DVRIP
 * e o ONVIF também trafegam isso. Todo texto que sai do app (log, mensagem de erro, tela) passa
 * por aqui antes.
 */
object Redact {
    private val regras = listOf(
        // caminho da URL RTSP das câmeras XM: usuário e hash da senha vão ali dentro
        // (sem \b: o "_" que antecede "password=" conta como letra e impediria o casamento)
        Regex("""((?:user|password)=)[^_&\s/"']+""") to "$1***",
        // usuário e senha no formato padrão de URL: rtsp://u:s@host
        Regex("""((?:rtsp|http|https)://)[^/\s:@]+:[^/\s@]+@""") to "$1***:***@",
        // JSON do DVRIP: "PassWord":"…", "UserName":"…"
        Regex("""(?i)("(?:PassWord|UserName)"\s*:\s*")[^"]*(")""") to "$1***$2",
        // SOAP do ONVIF: <Password …>…</Password>, <Username>…</Username>, <Nonce …>…</Nonce>
        Regex("""(?i)(<(?:\w+:)?(?:Password|Username|Nonce)\b[^>]*>)[^<]*(</)""") to "$1***$2",
    )

    fun texto(s: String): String = regras.fold(s) { r, (padrao, modelo) -> padrao.replace(r, modelo) }
}
