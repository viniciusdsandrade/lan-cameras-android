package com.lancameras.nucleo.onvif

import java.util.regex.Pattern

/**
 * Leitura mínima das respostas SOAP das câmeras: elas são simples e estáveis, e um interpretador
 * completo de XML não se justifica.
 */
object XmlMinimo {
    /** Textos de todos os elementos `<qualquer:tag>texto</...>`. */
    fun valores(xml: String, tag: String): List<String> {
        // o elemento pode ter atributos (`<s:Text xml:lang="en">`), mas não pode ser auto-fechado (`<x/>`)
        val re = Regex("<[^>]*\\b" + Pattern.quote(tag) + "(?:\\s[^>]*)?(?<!/)>([^<]*)<")
        return re.findAll(xml).map { desfazerEscape(it.groupValues[1]) }.toList()
    }

    /** Resumo de um SOAP Fault, ou null se a resposta é válida. */
    fun falha(xml: String): String? {
        if (!xml.contains("Fault")) return null
        val partes = valores(xml, "Text").filter { it.isNotBlank() }
        return if (partes.isEmpty()) "a câmera recusou o comando" else partes.joinToString("; ")
    }

    fun escapar(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun desfazerEscape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
