package com.indeni.ssh.utils

import java.io.{ByteArrayInputStream, InputStreamReader}
import java.nio.charset.Charset
import java.security.KeyPair

import org.apache.sshd.common.util.SecurityUtils
import org.bouncycastle.openssl.jcajce.{JcaPEMKeyConverter, JcePEMDecryptorProviderBuilder}
import org.bouncycastle.openssl.{PEMDecryptorProvider, PEMEncryptedKeyPair, PEMKeyPair, PEMParser}

import scala.util.control.NonFatal

object KeyPairUtils {
  def createKeyPair(key: String, passphrase: Option[String]): Option[KeyPair] = {
    if (!SecurityUtils.isBouncyCastleRegistered) throw new IllegalStateException("BouncyCastle is not registered as a JCE provider")

    val pemParser: PEMParser = new PEMParser(new InputStreamReader(new ByteArrayInputStream(key.getBytes(Charset.defaultCharset))))
    try {
      val pemConverter: JcaPEMKeyConverter = new JcaPEMKeyConverter
      pemConverter.setProvider("BC")
      val obj = {
        val keyPair: AnyRef = pemParser.readObject
        passphrase.fold(keyPair) { p =>
          keyPair match {
            case pem: PEMEncryptedKeyPair =>
              val decryptorBuilder: JcePEMDecryptorProviderBuilder = new JcePEMDecryptorProviderBuilder
              val pemDecryptor: PEMDecryptorProvider = decryptorBuilder.build(p.toCharArray)
              pemConverter.getKeyPair(pem.decryptKeyPair(pemDecryptor))
          }
        }
      }

      obj match {
        case kp: PEMKeyPair => Option(pemConverter.getKeyPair(kp))
        case kp: KeyPair => Option(kp)
        case _ => None
      }

    } catch {
      case NonFatal(e) =>
          pemParser.close()
          None
    }
  }
}
