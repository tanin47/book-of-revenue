package browsers

import controllers.routes

class RegisterSpec extends Base {
  before {
    clearLoggedInUserCookies()
    await(userService.deleteById(user.id))
    await(stripeAccountService.deleteById(stripeAccount.id))
  }

  it("registers and verifies email") {
    val username = "username"
    go(routes.AuthController.register().url)

    fill(tid("username"), username)
    fill(tid("password"), "1234")
    fill(tid("stripeApiKey"), stripeTestApikey)
    click(tid("submit-button"))

    waitUntil { getPath() == "/overview" }

    var user = await(userService.getByUsername(username)).get
    getLoggedInUserId() should be(Some(user.id))
  }

  it("validates") {
    go(routes.AuthController.register().url)

    fill(tid("username"), "")
    fill(tid("password"), "")
    fill(tid("stripeApiKey"), "")
    click(tid("submit-button"))
    waitUntil { elem(tid("submit-button")).getDomProperty("disabled") != "true" }
    checkErrorPanel("The username is required.", "The password is required.", "The Stripe API key is required.")

    fill(tid("username"), "new_user")
    fill(tid("password"), "1234")
    fill(tid("stripeApiKey"), "invalidkey")
    click(tid("submit-button"))
    waitUntil { elem(tid("submit-button")).getDomProperty("disabled") != "true" }
    checkErrorPanel("Invalid API Key provided: invalidkey")

    val user = makeUser()
    fill(tid("username"), "whatever")
    fill(tid("password"), "1234")
    fill(tid("stripeApiKey"), "invalid")
    click(tid("submit-button"))
    waitUntil { elem(tid("submit-button")).getDomProperty("disabled") != "true" }

    checkErrorPanel("Book of Revenue has already been setup. If you want to recover the access, please restart your Book of Revenue instance in the recovery mode.")
  }
}
