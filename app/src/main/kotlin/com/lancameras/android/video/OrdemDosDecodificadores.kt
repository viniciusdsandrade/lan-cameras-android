package com.lancameras.android.video

/** Um decodificador do aparelho, do jeito que a escolha precisa vê-lo. */
internal data class OpcaoDeDecodificador(val nome: String, val hardware: Boolean, val aceitaOTamanho: Boolean)

/**
 * A ordem em que os decodificadores são tentados: primeiro os que dizem aceitar o tamanho do vídeo, e
 * entre eles os de hardware. Os que já recusaram este mesmo vídeo neste aparelho vão para o fim (há
 * hardware que diz aceitar o tamanho e recusa na hora de configurar).
 */
internal fun ordemDeTentativa(opcoes: List<OpcaoDeDecodificador>, jaRecusaram: Set<String>): List<String> =
    opcoes.sortedWith(compareBy({ it.nome in jaRecusaram }, { !it.aceitaOTamanho }, { !it.hardware })).map { it.nome }
