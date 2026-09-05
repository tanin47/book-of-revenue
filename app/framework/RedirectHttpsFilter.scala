package framework

import org.apache.pekko.util.ByteString
import play.api.Logger
import play.api.libs.streams.Accumulator
import play.api.mvc.Results.Redirect
import play.api.mvc.{EssentialAction, EssentialFilter, RequestHeader, Result}
import play.filters.https.RedirectHttpsFilter as BaseRedirectHttpsFilter

import java.net.URI
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.net.ssl.{HttpsURLConnection, SSLHandshakeException}
import scala.concurrent.{ExecutionContext, Future}

class RedirectHttpsFilter @Inject() (base: BaseRedirectHttpsFilter, config: PlayConfig)(implicit ec: ExecutionContext) extends EssentialFilter {
  private[this] val logger = Logger(getClass)

  override def apply(next: EssentialAction): EssentialAction = { req =>
    val valid = checkDomainCertificate()

    if (
      req.host.startsWith("localhost") ||
      req.path.startsWith("/.well-known/acme-challenge") ||
      req.path.startsWith("/health-check") || // Health-checking is through localhost without https.
      req.path.startsWith("/onboard") ||
      req.path.startsWith("/assets")
    ) {
      next.apply(req)
    } else {
      if (valid) {
        // We redirect if the cert is valid.
        base.apply(next).apply(req)
      } else {
        // If the cert is invalid, we redirect to the onboard page.
        EssentialAction
          .apply { req => Accumulator.done(Future(Redirect(controllers.routes.HomeController.onboard()))) }
          .apply(req)
      }
    }
  }

  def checkDomainCertificate(): Boolean = {
    if (config.HAS_VALID_SSL_CERT.exists(_.isStillValid())) {
      return config.HAS_VALID_SSL_CERT.get.valid
    }

    val result = performCheckDomainCertificate()
    config.HAS_VALID_SSL_CERT = Some(PlayConfig.HasValidCert(result, Instant.now()))
    result
  }

  def performCheckDomainCertificate(): Boolean =  {
    val url = new URI(config.makeFullUrl("/health-check"))
    logger.info(s"Check the certificate of: ${url.toString()}")
    val connection = url.toURL.openConnection.asInstanceOf[HttpsURLConnection]

    try {
      connection.connect()
      val certs = connection.getServerCertificates()
      if (certs.nonEmpty && certs(0).isInstanceOf[X509Certificate]) {
        val cert = certs(0).asInstanceOf[X509Certificate]
        logger.info(s"Domain Name: ${config.BASE_URL}")
        logger.info(s"Subject DN: ${cert.getSubjectX500Principal}")
        logger.info(s"Issuer DN: ${cert.getIssuerX500Principal}")
        logger.info(s"Valid From: ${cert.getNotBefore}, Expires On: ${cert.getNotAfter}")
        cert.checkValidity()
        logger.info(s"The cert is valid")
        true
      } else {
        logger.info(s"The cert doesn't exist or is not X509Certificate.")
        false
      }
    } catch {
      case e: SSLHandshakeException =>
        // Handshake failure occurs if the CA is untrusted, or the hostname mismatches
        logger.warn(s"The cert is invalid. SSL Validation Failed for: ${e.getMessage}")
        false
      case e: Exception =>
        logger.warn(s"Error connecting to domain: ${e.getMessage}")
        false
    } finally {
      if (connection != null) connection.disconnect()
    }
  }
}
