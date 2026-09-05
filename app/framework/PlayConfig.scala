package framework

import play.api.i18n.{Langs, MessagesApi}
import play.api.{ConfigLoader, Configuration, Environment, Logger}

import javax.inject.{Inject, Singleton}

object PlayConfig {
  case class HasValidCert(valid: Boolean, lastChecked: Instant) {
    def isStillValid(): Boolean = {
      Instant.now().isBefore(lastChecked.plusSeconds(30))
    }
  }
}

@Singleton
class PlayConfig @Inject() (
  val config: Configuration,
  val env: Environment,
  val messagesApi: MessagesApi,
  val langs: Langs
) {

  private[this] val logger = Logger(getClass)

  val APP_DOMAIN: String = getString("app.domain")
  val BASE_URL: String = getString("app.baseUrl")
  val HTTPS_PORT: Option[String] = getOptString("https.port")

  var HAS_VALID_SSL_CERT: Option[PlayConfig.HasValidCert] = None

  def makeFullUrl(path: String): String = s"$BASE_URL$path"

  def getString(key: String): String = {
    getOptString(key)
      .filter(_.nonEmpty)
      .getOrElse {
        logger.error(
          s"The config '$key' doesn't exist in Play conf file or system properties"
        )
        sys.exit(1)
      }
  }

  def getSeq[T](key: String)(implicit loader: ConfigLoader[Seq[T]]): Seq[T] = {
    config.getOptional[Seq[T]](key).getOrElse {
      logger.error(
        s"The config '$key' doesn't exist in Play conf file or system properties"
      )
      sys.exit(1)
    }
  }

  def getInt(key: String): Int = {
    getString(key).toInt
  }

  def getBoolean(key: String): Boolean = {
    getOptString(key).exists(_.toBoolean)
  }

  def getOptString(key: String): Option[String] = {
    Option(System.getProperty(key))
      .orElse(config.getOptional[String](key))
      .map(_.trim)
  }
}
