package process

import base.Base

class HelpersSpec extends Base {
  it("handles mixed negative and positive") {
    Helpers.amortize(1234, Seq(-5, 10)) should be(Seq(-1234, 2468))
    Helpers.amortize(47, Seq(-5, 10)) should be(Seq(-47, 94))
    Helpers.amortize(5, Seq(-5, 10)) should be(Seq(-5, 10))
    Helpers.amortize(1, Seq(-5, 10)) should be(Seq(-1, 2))
    Helpers.amortize(2, Seq(-5, 10)) should be(Seq(-2, 4))
    Helpers.amortize(4, Seq(-5, 10)) should be(Seq(-4, 8))
    Helpers.amortize(2, Seq(-5, 10000)) should be(Seq(0, 2))
  }

  it("amortizes") {
    Helpers.amortize(950, Seq(760, 190)) should be(Seq(760, 190))
  }

  it("amortizes negative") {
    Helpers.amortize(-4, Seq(-8, -2)) should be(Seq(-4, 0))
    Helpers.amortize(-6, Seq(-8, -2)) should be(Seq(-5, -1))
    Helpers.amortize(-15, Seq(-8, -2)) should be(Seq(-12, -3))
  }
}
