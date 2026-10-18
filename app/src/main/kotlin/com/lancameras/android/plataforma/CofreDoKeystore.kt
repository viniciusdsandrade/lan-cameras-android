package com.lancameras.android.plataforma

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.lancameras.nucleo.modelo.Cofre
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifra o arquivo das câmeras (que leva o hash da senha) com uma chave AES-256 do Android Keystore:
 * a chave nunca sai do hardware do aparelho e morre com a desinstalação. Formato: 12 bytes de IV
 * seguidos do GCM (que também detecta adulteração).
 */
class CofreDoKeystore : Cofre {
    private fun chave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val gerador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gerador.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gerador.generateKey()
    }

    override fun cifrar(claro: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, chave())
        return c.iv + c.doFinal(claro)
    }

    override fun decifrar(cifrado: ByteArray): ByteArray {
        require(cifrado.size > IV) { "arquivo curto demais" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, chave(), GCMParameterSpec(128, cifrado, 0, IV))
        return c.doFinal(cifrado, IV, cifrado.size - IV)
    }

    private companion object {
        const val ALIAS = "cameras-de-casa"
        const val IV = 12
    }
}
