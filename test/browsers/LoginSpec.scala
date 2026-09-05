package browsers

import controllers.routes

class LoginSpec extends Base {

  it("logins correctly") {
    val user = makeUser(password = "1234")
    go(routes.AuthController.login(Some("/")).url)

    fill(tid("email"), user.username)
    fill(tid("password"), "1234")
    click(tid("submit-button"))

    waitUntil { getPath() == "/select-stripe-account" }

    getLoggedInUserId() should be(Some(user.id))
  }

  it("validates") {
    val user = makeUser(email = "test@test.com", password = "1234")
    go(routes.AuthController.login(Some("/")).url)

    fill(tid("email"), user.username)
    fill(tid("password"), "12346")
    click(tid("submit-button"))
    waitUntil { elem(tid("submit-button")).getDomProperty("disabled") != "true" }

    checkErrorPanel("The password is incorrect.")

    fill(tid("email"), "")
    fill(tid("password"), "")
    click(tid("submit-button"))
    waitUntil { elem(tid("submit-button")).getDomProperty("disabled") != "true" }

    checkErrorPanel("The username is required.", "The password is required.")

    fill(tid("email"), "some@nanakorn.com")
    fill(tid("password"), "123")
    click(tid("submit-button"))
    waitUntil { elem(tid("submit-button")).getDomProperty("disabled") != "true" }

    checkErrorPanel("The username doesn't exist.")

    getLoggedInUserId() should be(None)
  }
}
