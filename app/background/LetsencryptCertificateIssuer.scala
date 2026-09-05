package background

import database.services.{FileService, Http01ChallengeEntryService, TrackedExceptionService}
import framework.Helpers.await
import framework.PlayConfig
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.openssl.{PEMKeyPair, PEMParser}
import org.bouncycastle.openssl.jcajce.{JcaPEMKeyConverter, JcaPEMWriter}
import org.jobrunr.jobs.lambdas.{JobRequest, JobRequestHandler}
import org.shredzone.acme4j.challenge.Http01Challenge
import org.shredzone.acme4j.util.KeyPairUtils
import org.shredzone.acme4j.{AccountBuilder, Authorization, Session, Status}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.{Application, Environment, Logger, Mode, Play}

import java.io.{StringReader, StringWriter}
import java.security.{KeyPair, MessageDigest, PrivateKey, PublicKey}
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.IterableHasAsScala


case class LetsencryptCertificateIssuerRequest() extends JobRequest {
  def getJobRequestHandler(): Class[LetsencryptCertificateIssuer] = classOf[LetsencryptCertificateIssuer]
}

object LetsencryptCertificateIssuer {
  def main(args: Array[String]): Unit = {
    val app = GuiceApplicationBuilder(Environment.simple(mode = Mode.Dev)).build()

    Play.start(app)
    val handler = app.injector.instanceOf[LetsencryptCertificateIssuer]
    handler.run(LetsencryptCertificateIssuerRequest())
  }

  def readPrivateKey(pem: String): PrivateKey = {
    val parser = new PEMParser(new StringReader(pem))
    try {
      new JcaPEMKeyConverter().getPrivateKey(parser.readObject().asInstanceOf[PEMKeyPair].getPrivateKeyInfo)
    } finally {
      parser.close()
    }
  }

}

@Singleton
class LetsencryptCertificateIssuer @Inject() (
  fileService: FileService,
  http01ChallengeEntryService: Http01ChallengeEntryService,
  trackedExceptionService: TrackedExceptionService,
  config: PlayConfig,
  app: Application
)(implicit ec: ExecutionContext) extends BaseJobRequestHandler[LetsencryptCertificateIssuerRequest](trackedExceptionService) {
  import LetsencryptCertificateIssuer.*
  private val logger = Logger(getClass)

  def run2(req: LetsencryptCertificateIssuerRequest): Unit = {
    if (config.HTTPS_PORT.isEmpty || config.HTTPS_PORT.contains("disabled")) {
      logger.info("HTTPS is disabled, skipping certificate issuance")
      return
    }

    if (config.HAS_VALID_SSL_CERT.exists(_.valid)) {
      logger.info("SSL certificate is already valid, skipping certificate issuance")
      return
    }

    logger.info(s"Starting certificate issuance for ${config.APP_DOMAIN}")

    val acmeUrl = if (app.mode == Mode.Prod) {
      "https://acme-v02.api.letsencrypt.org/directory"
    } else {
      "https://acme-staging-v02.api.letsencrypt.org/directory"
    }
    logger.info(s"Using ACME URL: $acmeUrl")
    val session = new Session(acmeUrl)
    val accountKeyPair = getAccountKeyPair()
    logger.info(s"Account key pair: ${getKeyChecksum(accountKeyPair, "SHA-256")}")
    val account = new AccountBuilder()
      .agreeToTermsOfService()
      .useKeyPair(accountKeyPair)
      .createLogin(session)
    logger.info(s"Account: ${account.getAccount().getKeyIdentifier()}")

    val order = account.newOrder().domains(config.APP_DOMAIN).create();
    logger.info(s"A new order is made: ${order.getIdentifiers().asScala.map(_.getDomain)}")

    order.getAuthorizations.forEach { auth =>
      logger.info(s"Auth: ${auth.getIdentifier().getDomain()}, ${auth.getStatus()}, ${auth.getChallenges().asScala.map(_.getClass())}")
      if (auth.getStatus() == Status.PENDING) {
        val challenge = auth.getChallenges().asScala.find(_.isInstanceOf[Http01Challenge])
          .getOrElse { throw new Exception("http-01 challenge is not found") }
          .asInstanceOf[Http01Challenge]

        setUpHttp01Challenge(auth, challenge)

        challenge.trigger()
        logger.info(s"Triggered http-01 challenge")

        while (!Set(Status.VALID, Status.INVALID).contains(auth.getStatus())) {
          Thread.sleep(5000L)
          auth.fetch()
          logger.info(s"Auth status: ${auth.getStatus()}")
        }
      }
    }

    val domainKeyPair = KeyPairUtils.createKeyPair(2048)
    order.execute(domainKeyPair)
    logger.info(s"Executed order with the domain key pair")

    while (!Set(Status.VALID, Status.INVALID).contains(order.getStatus)) {
      Thread.sleep(5000L)
      order.fetch()
      logger.info(s"Order status: ${order.getStatus()}")
    }

    val cert = order.getCertificate()
    val chain = cert.getCertificateChain()

    val _ = await(fileService.writeLetsencryptDomainKey(toPem(domainKeyPair.getPrivate)))
    val __ = await(fileService.writeLetsencryptCertificateChain(toPem(chain.toArray*)))
    logger.info("Finished successfully")

    logger.info("Restarting the application...")
    await(app.stop())
    System.exit(0)
  }

  private def toPem(objects: AnyRef*): String = {
    val out = new StringWriter()
    val writer = new JcaPEMWriter(out)
    objects.foreach { writer.writeObject }
    writer.close()
    out.toString
  }

  private def setUpHttp01Challenge(auth: Authorization, challenge: Http01Challenge): Unit = {
    val _ = await(http01ChallengeEntryService.create(
      domain = auth.getIdentifier().getDomain(),
      token = challenge.getToken(),
      content = challenge.getAuthorization(),
    ))
    logger.info(s"Set up http-01 challenge: ${challenge.getToken()}")
  }

  private def getAccountKeyPair(): KeyPair = {
    (
      await(fileService.getLetsencryptAccountPrivateKey()),
      await(fileService.getLetsencryptAccountPublicKey())
    ) match {
      case (Some(privateKeyFile), Some(publicKeyFile)) =>
        new KeyPair(readPublicKey(publicKeyFile.content), readPrivateKey(privateKeyFile.content))

      case _ =>
        val accountKeyPair = KeyPairUtils.createKeyPair(2048)
        val _ = await(fileService.writeLetsencryptAccountPrivateKey(toPem(accountKeyPair.getPrivate)))
        val __ = await(fileService.writeLetsencryptAccountPublicKey(toPem(accountKeyPair.getPublic)))
        accountKeyPair
    }
  }
  private def readPublicKey(pem: String): PublicKey = {
    val parser = new PEMParser(new StringReader(pem))
    try {
      new JcaPEMKeyConverter().getPublicKey(parser.readObject().asInstanceOf[SubjectPublicKeyInfo])
    } finally {
      parser.close()
    }
  }

  def getKeyChecksum(keyPair: KeyPair, algorithm: String): String = {
    // Use public key (or keyPair.getPrivate() for private key)
    val keyBytes = keyPair.getPublic.getEncoded
    val digest = MessageDigest.getInstance(algorithm)
    val hashBytes = digest.digest(keyBytes)
    val hexString = new StringBuilder
    for (b <- hashBytes) {
      val hex = Integer.toHexString(0xff & b)
      if (hex.length == 1) hexString.append('0')
      hexString.append(hex)
    }
    hexString.toString
  }
}
