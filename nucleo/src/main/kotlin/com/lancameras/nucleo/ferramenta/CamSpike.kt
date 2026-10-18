package com.lancameras.nucleo.ferramenta

import com.lancameras.nucleo.Redact
import com.lancameras.nucleo.controle.ControleDeCamera
import com.lancameras.nucleo.modelo.ArmazenamentoDeCameras
import com.lancameras.nucleo.modelo.Cadastro
import com.lancameras.nucleo.modelo.CameraConfig
import com.lancameras.nucleo.modelo.StreamDeVideo
import com.lancameras.nucleo.onvif.DescobertaWs
import com.lancameras.nucleo.rede.VarreduraDeRede
import com.lancameras.nucleo.video.Decodificador
import com.lancameras.nucleo.video.FluxoDeVideo
import com.lancameras.nucleo.video.ParametrosDeVideo
import com.lancameras.nucleo.video.QuadroDeVideo
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

// Ferramenta de teste sem interface, direto nas câmeras: valida o núcleo no equipamento real (o app
// usa as mesmas peças). Só lê: não move, não muda luz nem rastreio, não toca som.
//
//   ./gradlew :nucleo:run --args="descobrir [--segundos N] [--salvar]"
//   ./gradlew :nucleo:run --args="varrer [--salvar]"
//   ./gradlew :nucleo:run --args="adicionar --ip ENDERECO [--salvar]"
//   ./gradlew :nucleo:run --args="estado [--camera NOME]"
//   ./gradlew :nucleo:run --args="video --camera NOME [--stream principal|secundario] [--segundos S]"
//   ./gradlew :nucleo:run --args="rtp --camera NOME [--stream principal|secundario] [--segundos S]"
//
// As câmeras ficam em CAMERAS_PASTA (o padrão é ~/.config/lan-cameras/camspike). Usuário e senha
// das câmeras nunca são impressos.

private class Opcoes(args: List<String>) {
    private val valores = mutableMapOf<String, String>()
    private val marcas = mutableSetOf<String>()

    init {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--") && i + 1 < args.size && !args[i + 1].startsWith("--")) {
                valores[a.removePrefix("--")] = args[i + 1]
                i += 2
            } else {
                if (a.startsWith("--")) marcas += a.removePrefix("--")
                i++
            }
        }
    }

    fun texto(nome: String) = valores[nome]
    fun numero(nome: String, padrao: Double) = valores[nome]?.toDoubleOrNull() ?: padrao
    fun tem(nome: String) = nome in marcas
}

private val DEPURAR = System.getenv("CAMSPIKE_DEPURAR") == "1"

private val armazenamento = ArmazenamentoDeCameras(
    ArmazenamentoDeCameras.pastaDe(File(System.getProperty("user.home"), ".config/lan-cameras/camspike")),
)

private fun sair(mensagem: String): Nothing {
    System.err.println(Redact.texto(mensagem))
    exitProcess(1)
}

private fun uso(): Nothing = sair(
    "uso: camspike descobrir|varrer|adicionar|estado|video [opções] (veja o começo de CamSpike.kt)",
)

private fun camerasEscolhidas(o: Opcoes): List<CameraConfig> {
    val todas = armazenamento.carregar()
    if (todas.isEmpty()) sair("nenhuma câmera guardada: rode `descobrir --salvar` ou `adicionar --ip … --salvar`")
    val nome = o.texto("camera") ?: return todas
    return todas.filter { it.nome.equals(nome, ignoreCase = true) }.ifEmpty { sair("não há câmera chamada \"$nome\"") }
}

private fun mostrar(r: Cadastro.Resultado, salvar: Boolean) {
    for (c in r.cameras) println("  ${c.nome}: modelo ${c.modelo ?: "?"}")
    for (f in r.falhas) println("  não identificado: ${Redact.texto(f)}")
    if (salvar && r.cameras.isNotEmpty()) {
        armazenamento.mesclar(r.cameras)
        println("guardadas: ${armazenamento.carregar().size} câmera(s)")
    }
}

private fun descobrir(o: Opcoes) {
    val segundos = o.numero("segundos", 4.0)
    val aparelhos = DescobertaWs.descobrir(segundos)
    println("WS-Discovery: ${aparelhos.size} aparelho(s) em ${segundos.toInt()} s")
    mostrar(Cadastro.identificarTodos(aparelhos.map { it.host }), o.tem("salvar"))
}

private fun varrer(o: Opcoes) {
    val inicio = System.nanoTime()
    val r = Cadastro.procurarPorVarredura()
    println("varredura: ${r.cameras.size} câmera(s) em ${"%.1f".format((System.nanoTime() - inicio) / 1e9)} s")
    mostrar(r, o.tem("salvar"))
}

private fun adicionar(o: Opcoes) {
    val host = o.texto("ip")?.let(Cadastro::enderecoValido) ?: sair("informe um endereço válido em --ip")
    mostrar(Cadastro.identificarTodos(listOf(host)), o.tem("salvar"))
}

private fun estado(o: Opcoes) {
    for (c in camerasEscolhidas(o)) {
        val e = ControleDeCamera(c, armazenamento).lerEstado()
        println("${c.nome}:")
        println("  luz: ${e.luz ?: "?"}")
        println("  rastreio: ${e.rastreioLigado ?: "?"}, volta em ${e.rastreioVoltaEmSegundos ?: "?"} s")
        println("  Wi-Fi: ${e.sinalDoWiFi ?: "?"}")
        println("  posições salvas: ${e.presets.joinToString { "${it.token}=${it.nome}" }.ifEmpty { "nenhuma" }}")
        for (a in e.avisos) println("  aviso: ${Redact.texto(a)}")
    }
}

/** "Decodifica" contando: o núcleo entrega os quadros prontos, o MediaCodec fica no app. */
private class Contador(private val aoExibir: (com.lancameras.nucleo.video.TamanhoDoVideo) -> Unit) : Decodificador {
    var parametros: ParametrosDeVideo? = null
    val chaves = AtomicInteger()
    val bytes = java.util.concurrent.atomic.AtomicLong()

    override fun configurar(parametros: ParametrosDeVideo) {
        this.parametros = parametros
        if (DEPURAR) println("  configurar: ${parametros.codec} tamanho=${parametros.tamanho} sps=${parametros.sps.size} bytes")
    }

    override fun decodificar(quadro: QuadroDeVideo): Boolean {
        val t = parametros?.tamanho
        if (DEPURAR) println("  quadro ${quadro.dados.size} bytes chave=${quadro.chave} t=${quadro.tempoUs / 1000} ms -> ${t != null}")
        if (t == null) return false
        if (quadro.chave) chaves.incrementAndGet()
        bytes.addAndGet(quadro.dados.size.toLong())
        aoExibir(t)
        return true
    }

    override fun encerrar() {}
}

private fun video(o: Opcoes) {
    val stream = if (o.texto("stream") == "secundario") StreamDeVideo.SECUNDARIO else StreamDeVideo.PRINCIPAL
    val segundos = o.numero("segundos", 5.0)
    for (c in camerasEscolhidas(o)) {
        var contador: Contador? = null
        val fluxo = FluxoDeVideo(c.urlDeVideo(stream)) { _, aoExibir -> Contador(aoExibir).also { contador = it } }
        fluxo.aoMudarEstado = { println("  [${c.nome}] ${Redact.texto(it.toString())}") }
        fluxo.iniciar()
        Thread.sleep((segundos * 1000).toLong())
        val quadros = fluxo.quadrosRecebidos
        fluxo.parar()
        val cont = contador
        println(
            "${c.nome} (${stream.name.lowercase()}): $quadros quadros em ${segundos.toInt()} s " +
                "(${"%.1f".format(quadros / segundos)} q/s), ${cont?.chaves?.get() ?: 0} de referência, " +
                "${"%.0f".format((cont?.bytes?.get() ?: 0) * 8 / segundos / 1000)} kbit/s",
        )
    }
    Thread.sleep(500)                                                   // deixa o TEARDOWN sair
}

/** O que chega da câmera, camada por camada: pacotes RTP, quadros remontados, quadros liberados. */
private fun rtp(o: Opcoes) {
    val stream = if (o.texto("stream") == "secundario") StreamDeVideo.SECUNDARIO else StreamDeVideo.PRINCIPAL
    val segundos = o.numero("segundos", 5.0)
    for (c in camerasEscolhidas(o)) {
        val cliente = com.lancameras.nucleo.rtsp.ClienteRtsp.criar(c.urlDeVideo(stream)) ?: sair("endereço inválido")
        var depacotador: com.lancameras.nucleo.video.Depacotador? = null
        var preparador: com.lancameras.nucleo.video.PreparadorDeQuadros? = null
        var pacotes = 0
        var bytes = 0L
        var marcadores = 0
        var saltos = 0
        var ultimaSeq = -1
        var aus = 0
        var chaves = 0
        var liberados = 0
        var inicio = 0L
        val tipos = sortedMapOf<Int, Int>()
        val parada = Thread { Thread.sleep((segundos * 1000).toLong()); cliente.parar() }
        val t0 = System.nanoTime()
        try {
            cliente.executar(
                incluirAudio = false,
                aoFicarPronto = { sdp ->
                    val v = sdp.midias.first { it.tipo == "video" }
                    val codec = com.lancameras.nucleo.video.CodecDeVideo.doSdp(v.codificacao) ?: sair("codec ${v.codificacao}")
                    depacotador = com.lancameras.nucleo.video.Depacotador.para(codec)
                    preparador = com.lancameras.nucleo.video.PreparadorDeQuadros(codec).also { it.semear(v.conjuntosDeParametros) }
                    println("  [${c.nome}] SDP em ${(System.nanoTime() - t0) / 1_000_000} ms: ${v.codificacao}, ${v.conjuntosDeParametros.size} parâmetros")
                    parada.start()
                },
                aoReceberVideo = { p ->
                    if (pacotes == 0) inicio = System.nanoTime()
                    pacotes++
                    bytes += p.carga.size
                    if (p.marcador) marcadores++
                    if (ultimaSeq >= 0 && p.sequencia != ((ultimaSeq + 1) and 0xFFFF)) saltos++
                    ultimaSeq = p.sequencia
                    val b0 = p.carga.firstOrNull()?.toInt() ?: 0
                    val tipo = if (preparador?.codec == com.lancameras.nucleo.video.CodecDeVideo.H265) (b0 shr 1) and 0x3F else b0 and 0x1F
                    tipos[tipo] = (tipos[tipo] ?: 0) + 1
                    for (au in depacotador!!.empurrar(p)) {
                        aus++
                        if (au.chave) chaves++
                        if (preparador!!.preparar(au) != null) liberados++
                    }
                },
            )
        } catch (e: Exception) {
            println("  [${c.nome}] terminou com erro: $e")
        }
        val dur = if (inicio > 0) (System.nanoTime() - inicio) / 1e9 else 0.0
        println("${c.nome}: $pacotes pacotes (${"%.0f".format(bytes * 8 / maxOf(dur, 0.001) / 1000)} kbit/s em ${"%.1f".format(dur)} s), " +
            "$marcadores marcadores, $saltos saltos de sequência, tipos de carga $tipos")
        println("  quadros remontados $aus ($chaves de referência), liberados $liberados, descartados ${preparador?.descartados ?: 0}")
    }
}

fun main(args: Array<String>) {
    val comando = args.firstOrNull() ?: uso()
    val o = Opcoes(args.drop(1))
    try {
        when (comando) {
            "descobrir" -> descobrir(o)
            "varrer" -> varrer(o)
            "adicionar" -> adicionar(o)
            "estado" -> estado(o)
            "video" -> video(o)
            "rtp" -> rtp(o)
            else -> uso()
        }
    } catch (e: VarreduraDeRede.Falha) {
        sair(e.message ?: "falha na varredura")
    } catch (e: Exception) {
        sair("erro: $e")
    }
    exitProcess(0)
}
