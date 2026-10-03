package browsers

import org.openqa.selenium.support.ui.{ExpectedConditions, WebDriverWait}

import java.sql.DriverManager
import java.time.Duration
import scala.util.Using


class SettingsSpec extends Base {
  it("reset the Metronome password", user) {
    go("/settings")

    click(tid("generateMetronomePasswordButton"))

    val wait = new WebDriverWait(webDriver, Duration.ofSeconds(10))
    val confirm = wait.until(ExpectedConditions.alertIsPresent)
    confirm.accept()

    waitUntil { elem(tid("metronomeNewPassword")).getText.nonEmpty }
    val password = elem(tid("metronomeNewPassword")).getText
    println(password)
    val detail = await(metronomeDataExportPostgresUserService.getMetronomeDataExportDetail())

    Using.resource(
      DriverManager.getConnection(
        s"jdbc:postgresql://${detail.host}:${detail.port}/${detail.databaseName}",
        detail.username,
        password
      )
    ) { conn =>
      Using.resource(conn.createStatement()) { stmt =>
        Using.resource(stmt.executeQuery(s"SELECT COUNT(*) FROM ${detail.schemaName}.invoice")) { rs =>
          rs.next() should be(true)
          rs.getLong(1) should be(0L)
        }
      }
    }
  }
}
