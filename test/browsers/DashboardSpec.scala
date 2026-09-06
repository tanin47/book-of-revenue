package browsers

import framework.Instant

class DashboardSpec extends Base {

  describe("clicks on the AR aging chart's audit link") {
    before {
      Instant.mockTimeForTest(java.time.Instant.parse("2026-09-06T00:00:00Z"))
    }

    it("current month", user) {
      go("/")

      hover(Seq(tid("ar-aging-chart"), "canvas").mkString(" "), 10, 20)
      waitUntil { hasElem(".bar-tooltip") }
      click(".bar-tooltip a")

      waitUntil { getPath().startsWith("/ar-aging") }
      waitUntil { elem(tid("date")).getText == "2026-09-06 (end of day)" }
    }

    it("previous month", user) {
      go("/")

      click(tid("filterButton"))
      select(tid("periodEnd"), "2026-08")
      click(tid("submitButton"))

      waitUntil { elem(tid("date")).getText == "2025-10 to 2026-08" }

      hover(Seq(tid("ar-aging-chart"), "canvas").mkString(" "), 10, 20)
      waitUntil { hasElem(".bar-tooltip") }
      click(".bar-tooltip a")

      waitUntil { getPath().startsWith("/ar-aging") }
      waitUntil { elem(tid("date")).getText == "2026-08-31 (end of day)" }
    }
  }

}
