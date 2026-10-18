package com.lancameras.nucleo.modelo

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * Protege em repouso o arquivo das câmeras (que leva o hash da senha). No app, é uma chave AES-GCM
 * do Android Keystore; nos testes e na ferramenta de linha de comando, [SemCofre].
 */
interface Cofre {
    fun cifrar(claro: ByteArray): ByteArray

    /** Lança se o conteúdo foi adulterado ou a chave se perdeu. */
    fun decifrar(cifrado: ByteArray): ByteArray
}

object SemCofre : Cofre {
    override fun cifrar(claro: ByteArray) = claro
    override fun decifrar(cifrado: ByteArray) = cifrado
}

/**
 * Guarda as câmeras (com o hash da senha) e as posições salvas pelo app, numa [pasta] que só o
 * próprio app enxerga (no Android, `filesDir`, que fica fora de qualquer backup).
 *
 * As câmeras passam pelo [cofre] antes de ir para o disco; as posições salvas (só números que a
 * câmera deu) não têm segredo.
 */
class ArmazenamentoDeCameras(private val pasta: File, private val cofre: Cofre = SemCofre) {
    private val trava = Any()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializadorDeCameras = ListSerializer(CameraConfig.serializer())
    private val serializadorDePresets = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    private val arquivoDeCameras get() = File(pasta, "cameras.json")
    private val arquivoDePresets get() = File(pasta, "presets.json")

    // Câmeras

    fun carregar(): List<CameraConfig> = synchronized(trava) { lerCameras() }.sortedBy { it.ordem }

    fun salvar(cameras: List<CameraConfig>) = synchronized(trava) { gravar(json.encodeToString(serializadorDeCameras, cameras), arquivoDeCameras, cifrar = true) }

    /** Acrescenta ou atualiza (por `id`) sem perder o nome e a ordem que o usuário já deu. */
    fun mesclar(novas: List<CameraConfig>) = synchronized(trava) {
        val atuais = lerCameras().toMutableList()
        for (n in novas) {
            val i = atuais.indexOfFirst { it.id == n.id }
            if (i >= 0) {
                atuais[i] = n.copy(nome = atuais[i].nome, ordem = atuais[i].ordem, duasLentes = atuais[i].duasLentes)
            } else {
                atuais += n.copy(ordem = (atuais.maxOfOrNull { it.ordem } ?: -1) + 1)
            }
        }
        gravar(json.encodeToString(serializadorDeCameras, atuais), arquivoDeCameras, cifrar = true)
    }

    fun atualizar(id: String, mudanca: (CameraConfig) -> CameraConfig) = synchronized(trava) {
        val atuais = lerCameras().toMutableList()
        val i = atuais.indexOfFirst { it.id == id }
        if (i < 0) return@synchronized
        atuais[i] = mudanca(atuais[i])
        gravar(json.encodeToString(serializadorDeCameras, atuais), arquivoDeCameras, cifrar = true)
    }

    private fun lerCameras(): List<CameraConfig> {
        if (!arquivoDeCameras.exists()) return emptyList()
        return try {
            val texto = cofre.decifrar(arquivoDeCameras.readBytes()).toString(Charsets.UTF_8)
            json.decodeFromString(serializadorDeCameras, texto)
        } catch (_: Exception) {
            // arquivo ilegível (chave perdida, adulterado, pela metade): recomeça vazio, a câmera se cadastra de novo
            emptyList()
        }
    }

    // Posições salvas por este app (só estas o app pode apagar)

    fun presetsDoApp(camera: String): Set<String> = synchronized(trava) { lerPresets()[camera].orEmpty().toSet() }

    fun registrarPreset(token: String, camera: String) = synchronized(trava) {
        val todos = lerPresets().toMutableMap()
        val lista = todos[camera].orEmpty()
        todos[camera] = if (token in lista) lista else lista + token
        gravar(json.encodeToString(serializadorDePresets, todos), arquivoDePresets, cifrar = false)
    }

    fun esquecerPreset(token: String, camera: String) = synchronized(trava) {
        val todos = lerPresets().toMutableMap()
        todos[camera] = todos[camera].orEmpty().filter { it != token }
        gravar(json.encodeToString(serializadorDePresets, todos), arquivoDePresets, cifrar = false)
    }

    private fun lerPresets(): Map<String, List<String>> = try {
        if (arquivoDePresets.exists()) json.decodeFromString(serializadorDePresets, arquivoDePresets.readText()) else emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    // Gravação segura

    /**
     * Grava num arquivo temporário só do dono e troca de lugar: nunca deixa um arquivo pela metade
     * nem, por um instante que seja, legível por outros usuários.
     */
    private fun gravar(texto: String, destino: File, cifrar: Boolean) {
        pasta.mkdirs()
        apenasDono(pasta, diretorio = true)
        val bytes = texto.toByteArray(Charsets.UTF_8).let { if (cifrar) cofre.cifrar(it) else it }
        val temporario = File(pasta, ".${destino.name}.${UUID.randomUUID()}.tmp")
        try {
            criarSoDoDono(temporario)
            temporario.writeBytes(bytes)
            try {
                Files.move(temporario.toPath(), destino.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporario.toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            temporario.delete()
            throw e
        }
        apenasDono(destino, diretorio = false)
    }

    private fun criarSoDoDono(f: File) {
        try {
            Files.createFile(f.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (_: UnsupportedOperationException) {
            f.createNewFile()
        }
    }

    private fun apenasDono(f: File, diretorio: Boolean) {
        try {
            Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString(if (diretorio) "rwx------" else "rw-------"))
        } catch (_: UnsupportedOperationException) {
            f.setReadable(false, false); f.setReadable(true, true)
            f.setWritable(false, false); f.setWritable(true, true)
            if (diretorio) { f.setExecutable(false, false); f.setExecutable(true, true) }
        }
    }

    companion object {
        /** `CAMERAS_PASTA` no ambiente (ferramenta de linha de comando e testes); senão, [padrao]. */
        fun pastaDe(padrao: File): File = System.getenv("CAMERAS_PASTA")?.takeIf { it.isNotEmpty() }?.let(::File) ?: padrao
    }
}
