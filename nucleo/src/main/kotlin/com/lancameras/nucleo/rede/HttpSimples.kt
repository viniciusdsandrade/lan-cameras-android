package com.lancameras.nucleo.rede

class RespostaHttp(val status: Int, val corpo: ByteArray) {
    val texto: String get() = corpo.toString(Charsets.UTF_8)
}

/**
 * Cliente HTTP mínimo (POST, uma requisição por conexão) para o SOAP das câmeras.
 *
 * Escrito sobre [SoqueteTcp] em vez da pilha HTTP do sistema para não depender de proxy, de
 * reaproveitamento de conexão (que o servidor das câmeras trata mal) nem de liberar tráfego em claro.
 */
object HttpSimples {
    fun post(host: String, porta: Int, caminho: String, corpo: String, tipo: String, tempo: Double = 6.0): RespostaHttp {
        val s = SoqueteTcp()
        s.conectar(host, porta, minOf(tempo, 5.0))
        try {
            val dados = corpo.toByteArray(Charsets.UTF_8)
            val cabecalho = "POST $caminho HTTP/1.1\r\nHost: $host:$porta\r\nContent-Type: $tipo\r\n" +
                "Content-Length: ${dados.size}\r\nConnection: close\r\nUser-Agent: CamerasDeCasa\r\n\r\n"
            s.escrever(cabecalho.toByteArray(Charsets.UTF_8) + dados)

            var buf = ByteArray(0)
            val limite = System.nanoTime() + (tempo * 1e9).toLong()
            while (true) {
                interpretar(buf, fimDaConexao = false)?.let { return it }
                val restante = (limite - System.nanoTime()) / 1e9
                if (restante <= 0) throw ErroDeRede.TempoEsgotado("resposta da câmera")
                try {
                    s.ler(65536, minOf(1.0, restante))?.let { buf += it }
                } catch (_: ErroDeRede.Fechado) {
                    interpretar(buf, fimDaConexao = true)?.let { return it }
                    throw ErroDeRede.Fechado()
                }
            }
        } finally {
            s.fechar()
        }
    }

    /** Devolve a resposta se já estiver completa (ou se a conexão acabou), senão null. */
    fun interpretar(b: ByteArray, fimDaConexao: Boolean): RespostaHttp? {
        val fim = indice(byteArrayOf(13, 10, 13, 10), b) ?: return null
        val linhas = b.copyOfRange(0, fim).toString(Charsets.UTF_8).split("\r\n").toMutableList()
        val primeira = linhas.removeAt(0).split(" ").filter { it.isNotEmpty() }
        val status = primeira.getOrNull(1)?.toIntOrNull()
        if (primeira.size < 2 || !primeira[0].startsWith("HTTP/") || status == null) throw ErroDeRede.Sistema("resposta HTTP inválida")
        val cab = HashMap<String, String>()
        for (l in linhas) {
            val i = l.indexOf(':')
            if (i < 0) continue
            cab[l.substring(0, i).lowercase()] = l.substring(i + 1).trim()
        }
        val corpo = b.copyOfRange(fim + 4, b.size)
        if (cab["transfer-encoding"]?.lowercase()?.contains("chunked") == true) {
            val inteiro = desfazerChunked(corpo) ?: return if (fimDaConexao) RespostaHttp(status, corpo) else null
            return RespostaHttp(status, inteiro)
        }
        val tam = cab["content-length"]?.toIntOrNull()
        if (tam != null) return if (corpo.size >= tam) RespostaHttp(status, corpo.copyOf(tam)) else null
        return if (fimDaConexao) RespostaHttp(status, corpo) else null
    }

    /** Junta os pedaços de um corpo "chunked"; null enquanto o último pedaço não chegou. */
    fun desfazerChunked(c: ByteArray): ByteArray? {
        var saida = ByteArray(0)
        var i = 0
        while (true) {
            val f = indice(byteArrayOf(13, 10), c, i) ?: return null
            val tamTexto = c.copyOfRange(i, f).toString(Charsets.UTF_8).split(";").first().trim()
            val tam = tamTexto.toIntOrNull(16) ?: return null
            i = f + 2
            if (tam == 0) return saida
            if (c.size < i + tam + 2) return null
            saida += c.copyOfRange(i, i + tam)
            i += tam + 2
        }
    }

    fun indice(padrao: ByteArray, b: ByteArray, inicio: Int = 0): Int? {
        if (b.size < padrao.size || inicio > b.size - padrao.size) return null
        var i = inicio
        while (i <= b.size - padrao.size) {
            if (b[i] == padrao[0] && (padrao.indices).all { b[i + it] == padrao[it] }) return i
            i++
        }
        return null
    }
}
