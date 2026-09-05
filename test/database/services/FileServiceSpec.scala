package database.services

import base.Base

class FileServiceSpec extends Base {
  it("writes and reads text content") {
    val content = "-----BEGIN PRIVATE KEY-----\nabc123\n-----END PRIVATE KEY-----\n"

    val created = await(fileService.create("some-file.pem", content))
    created.name should be("some-file.pem")
    created.content should be(content)

    val retrieved = await(fileService.getByName("some-file.pem")).get
    retrieved.name should be("some-file.pem")
    retrieved.content should be(content)
  }

  it("updates the content when the name already exists") {
    val _ = await(fileService.create("dup.pem", "first"))
    val __ = await(fileService.create("dup.pem", "second"))

    await(fileService.getByName("dup.pem")).get.content should be("second")
  }
}
