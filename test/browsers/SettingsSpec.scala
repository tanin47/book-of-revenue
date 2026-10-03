package browsers

import org.openqa.selenium.support.ui.{ExpectedConditions, WebDriverWait}

import java.sql.DriverManager
import java.time.Duration
import scala.util.Using


class SettingsSpec extends Base {
  it("reset the Metronome password", user) {
    go("/settings")

    click(tid("generateMetronomePasswordButton"))
    acceptConfirmDialog()

    waitUntil { elem(tid("metronomeNewPassword")).getText.nonEmpty }
    val password = elem(tid("metronomeNewPassword")).getText
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

  it("doesn't allow resetting the Metronome password in the public demo mode", user) {
    config.IS_PUBLIC_DEMO = true

    go("/settings")
    click(tid("generateMetronomePasswordButton"))
    acceptConfirmDialog()
    checkErrorPanel("This is a demo version, generating a new password is not allowed.")
  }
}
