package com.lancameras.nucleo.rede

/** Falhas de rede com mensagens para a tela (sem endereço nem credencial). */
sealed class ErroDeRede(mensagem: String) : Exception(mensagem) {
    class Resolucao(m: String) : ErroDeRede("não achei o endereço da câmera ($m)")
    class Conexao(m: String) : ErroDeRede("não consegui conectar ($m)")
    class TempoEsgotado(o: String) : ErroDeRede("tempo esgotado: $o")
    class Fechado : ErroDeRede("conexão fechada")
    class Sistema(m: String) : ErroDeRede("erro de rede ($m)")

    override fun toString(): String = message ?: "erro de rede"
}
