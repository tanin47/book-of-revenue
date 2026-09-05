package framework

import background.LetsencryptCertificateIssuer.readPrivateKey
import database.services.FileService
import framework.Helpers.await
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.{Extension, GeneralName, GeneralNames}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import play.core.ApplicationProvider
import play.server.api.SSLEngineProvider

import java.io.{ByteArrayInputStream, StringReader}
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.cert.{Certificate, CertificateFactory}
import java.security.{KeyPairGenerator, KeyStore, PrivateKey, Security}
import java.time.Duration
import java.util.Date
import javax.inject.Inject
import javax.net.ssl.{KeyManagerFactory, SSLContext, SSLEngine, TrustManagerFactory}
import scala.jdk.CollectionConverters.CollectionHasAsScala

class SslEngineProvider @Inject() (appProvider: ApplicationProvider) extends SSLEngineProvider {
  Security.addProvider(new BouncyCastleProvider)

  override def createSSLEngine(): SSLEngine = {
    sslContext().createSSLEngine
  }

  override def sslContext(): SSLContext = {
    val fileService = appProvider.get.get.injector.instanceOf[FileService]
    val config = appProvider.get.get.injector.instanceOf[PlayConfig]
    val password: Array[Char] = "opensesame".toCharArray
    val alias = "cert"

    val privateKey = await(fileService.getLetsencryptDomainKey())
      .map { f => readPrivateKey(f.content) }
      .getOrElse {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC")
        keyPairGenerator.initialize(2048)
        keyPairGenerator.generateKeyPair.getPrivate
      }

    val chain = await(fileService.getLetsencryptCertificateChain())
      .map { f => readCertificateChain(f.content) }
      .getOrElse {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair

        val dnName = new X500Name(s"CN=${config.APP_DOMAIN}, O=Development, C=US")
        val serialNumber = BigInteger.valueOf(System.currentTimeMillis)

        val now: Instant = Instant.now()
        val notBefore = Date.from(now)
        val notAfter = Date.from(now.plus(Duration.ofDays(365)))

        val certBuilder = new JcaX509v3CertificateBuilder(
          dnName,
          serialNumber,
          notBefore,
          notAfter,
          dnName,
          keyPair.getPublic
        )

        val subjectAltName = new GeneralNames(new GeneralName(GeneralName.dNSName, config.APP_DOMAIN))
        certBuilder.addExtension(Extension.subjectAlternativeName, false, subjectAltName)

        val signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(keyPair.getPrivate)

        val certHolder = certBuilder.build(signer)
        Array(new JcaX509CertificateConverter().setProvider("BC").getCertificate(certHolder).asInstanceOf[Certificate])
      }

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    keyStore.load(null, null)
    keyStore.setKeyEntry(alias, privateKey, password, chain)

    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(keyStore, password)

    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(keyStore) // Trust the self-signed certificate implicitly

    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    sslContext.init(kmf.getKeyManagers, tmf.getTrustManagers, null)
    sslContext
  }


  private def readCertificateChain(pem: String): Array[Certificate] = {
    val factory = CertificateFactory.getInstance("X.509")
    val input = new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8))
    try {
      factory.generateCertificates(input).asScala.toArray
    } finally {
      input.close()
    }
  }
}
