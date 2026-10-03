package browsers

import framework.Instant

class SettingsSpec extends Base {

  describe("Metronome data export") {
    it("reset the password", user) {
      go("/settings")

      click("generateMetronomePasswordButton")

      waitUntil { elem(tid("metronomeNewPassword")).getText.nonEmpty }
      val password = elem(tid("metronomeNewPassword")).getText
      println(password)
    }
  }
}
