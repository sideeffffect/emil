package emil.javamail

import java.nio.charset.Charset

import cats.data.NonEmptyList
import cats.effect._
import cats.effect.unsafe.implicits.global
import emil._
import emil.builder._
import emil.javamail.conv._
import emil.javamail.syntax._
import jakarta.mail.internet.InternetAddress
import munit._

class MailConvTest extends FunSuite {
  def toStringContent(body: MailBody[IO]): MailBody[IO] = {
    def mkString(ios: IO[BodyContent]): BodyContent =
      BodyContent(ios.unsafeRunSync().asString)

    body.fold(
      identity,
      txt => MailBody.text(mkString(txt.text)),
      html => MailBody.html(mkString(html.html)),
      both => MailBody.both(mkString(both.text), mkString(both.html))
    )
  }

  test("write mail address") {
    val ma1 = MailAddress.unsafe(Some("a;b;c"), "me@localhost")
    val ma2 = MailAddress.unsafe(Some("a;b;c"), "me<@localhost")
    val ma3 = MailAddress.unsafe(Some("Company name"), "some.name@some.domain.com")
    val addrConv = encode.mailAddressEncode

    assertEquals(addrConv.convert(ma1), new InternetAddress("me@localhost"))
    intercept[Exception](addrConv.convert(ma2))
    assertEquals(
      addrConv.convert(ma3),
      new InternetAddress("\"Company name\" <some.name@some.domain.com>")
    )
    assertEquals(addrConv.convert(ma3), new InternetAddress(ma3.displayString))
  }

  test("write text mail") {
    val mail = MailBuilder.build[IO](
      From("test@test.com"),
      To("test@test.com"),
      Subject("Hello"),
      TextBody("This is text"),
      MessageID("<bla>")
    )

    val str = mail.serialize
      .unsafeRunSync()
      .replaceAll("Date:.*", "Date: Sun, 27 Oct 2019 10:15:36 +0100 (CET)")
    assertEquals(
      str,
      """Date: Sun, 27 Oct 2019 10:15:36 +0100 (CET)
        |From: test@test.com
        |To: test@test.com
        |Message-ID: <bla>
        |Subject: Hello
        |MIME-Version: 1.0
        |Content-Type: text/plain; charset=utf-8
        |Content-Transfer-Encoding: 7bit
        |
        |This is text""".stripMargin.replace("\n", "\r\n")
    )
  }

  test("write html mail") {
    val mail = MailBuilder.build[IO](
      From("test@test.com"),
      To("test@test.com"),
      Subject("Hello"),
      HtmlBody("<p>This is html</p>"),
      MessageID("<bla>")
    )

    val str = mail.serialize
      .unsafeRunSync()
      .replaceAll("Date:.*", "Date: Sun, 27 Oct 2019 10:15:36 +0100 (CET)")
    assertEquals(
      str,
      """Date: Sun, 27 Oct 2019 10:15:36 +0100 (CET)
        |From: test@test.com
        |To: test@test.com
        |Message-ID: <bla>
        |Subject: Hello
        |MIME-Version: 1.0
        |Content-Type: text/html; charset=utf-8
        |Content-Transfer-Encoding: 7bit
        |
        |<p>This is html</p>""".stripMargin.replace("\n", "\r\n")
    )
  }

  test("write empty mail") {
    val mail = MailBuilder.build[IO](
      From("test@test.com"),
      To("test@test.com"),
      Subject("Hello"),
      MessageID("<bla>")
    )

    val str = mail.serialize
      .unsafeRunSync()
      .replaceAll("Date:.*", "Date: Sun, 27 Oct 2019 10:15:36 +0100 (CET)")
    assertEquals(
      str,
      """Date: Sun, 27 Oct 2019 10:15:36 +0100 (CET)
        |From: test@test.com
        |To: test@test.com
        |Message-ID: <bla>
        |Subject: Hello
        |MIME-Version: 1.0
        |Content-Type: text/plain; charset=utf-8
        |Content-Transfer-Encoding: 7bit
        |
        |""".stripMargin.replace("\n", "\r\n")
    )
  }

  test("write alternative mail") {
    val mail = MailBuilder.build[IO](
      From("test@test.com"),
      To("test@test.com"),
      Subject("Hello"),
      HtmlBody("<p>This is html</p>"),
      TextBody("This is html as text"),
      MessageID("<bla>")
    )

    val str = mail.serialize
      .unsafeRunSync()
      .replaceAll("Date:.*", "Date: Sun, 27 Oct 2019 10:15:36 +0100 (CET)")

    val partChars = str.linesIterator
      .find(_.contains("boundary"))
      .map(_.trim.drop(10).dropRight(1))
      .getOrElse(sys.error("no part boundary found"))

    val expected = s"""Date: Sun, 27 Oct 2019 10:15:36 +0100 (CET)
                      |From: test@test.com
                      |To: test@test.com
                      |Message-ID: <bla>
                      |Subject: Hello
                      |MIME-Version: 1.0
                      |Content-Type: multipart/alternative;[]
                      |{}boundary="$partChars"
                      |
                      |--$partChars
                      |Content-Type: text/plain; charset=utf-8
                      |Content-Transfer-Encoding: 7bit
                      |
                      |This is html as text
                      |--$partChars
                      |Content-Type: text/html; charset=utf-8
                      |Content-Transfer-Encoding: 7bit
                      |
                      |<p>This is html</p>
                      |--$partChars--
                      |""".stripMargin
      .replace("\n", "\r\n")
      .replace("{}", "\t")
      .replace("[]", " ")

    assertEquals(str, expected)
  }

  test("Write attachment with filename and implicit content disposition") {
    val htmlBody = "<h1>Hello!</h1>\n<p>This <b>is</b> a mail.</p>"
    val mail: Mail[IO] = MailBuilder.build(
      From("from@example.com"),
      To("to@example.com"),
      Subject("Hello!"),
      UserAgent("my-email-client"),
      TextBody("Hello!\n\nThis is a mail."),
      HtmlBody(htmlBody),
      Attach(Attachment.textPlain[IO]("hello world!"))
        .withFilename("test.txt")
    )

    val str = mail.serialize.unsafeRunSync()
    assert(str.nonEmpty)
    assert(str.contains("Content-Disposition: attachment; filename=test.txt"))
    assert(str.contains("Content-Type: multipart/mixed;"),
      clue = "Without inlined attachments the content-type should be multipart/mixed")

  }

  test("Write attachment with filename and declared content disposition") {
    val htmlBody = "<h1>Hello!</h1>\n<p>This <b>is</b> a mail.</p>"
    val mail: Mail[IO] = MailBuilder.build(
      From("from@example.com"),
      To("to@example.com"),
      Subject("Hello!"),
      UserAgent("my-email-client"),
      TextBody("Hello!\n\nThis is a mail."),
      HtmlBody(htmlBody),
      Attach(Attachment.textPlain[IO]("hello world!"))
        .withFilename("test.txt")
        .withDisposition(Disposition.Inline)
    )

    val str = mail.serialize.unsafeRunSync()
    assert(str.nonEmpty)
    assert(str.contains("Content-Disposition: inline; filename=test.txt"))
  }

  test("Write attachment with filename and declared content disposition with ID") {
    val htmlBody = """<h1>Hello!</h1><p>This <b>is</b> a mail from Emil!</p><br/><img src="cid:img01.png@42342343"/>"""
    val mail: Mail[IO] = MailBuilder.build(
      From("emil@example.com"),
      To("to@example.com"),
      Subject("Hello!"),
      UserAgent("my-emil-client"),
      TextBody("Hello!\n\nThis is a mail from Emil."),
      HtmlBody(htmlBody),
      AttachUrl[IO](getClass.getResource( "/attachments/logo.png"), mimeType = MimeType("image", "png"))
        .withFilename("img01.png")
        .withInlinedContentId("img01.png@42342343"),
      AttachUrl[IO](getClass.getResource( "/attachments/logo.png"), mimeType = MimeType("image", "png"))
        .withFilename("emil-logo.png")
        .withDisposition(Disposition.Attachment)
    )

    val str = mail.serialize.unsafeRunSync()
    assert(str.nonEmpty)
    assert(str.contains("Content-Type: multipart/related;"),
      clue = "Inlined attachment should change the content-type to multipart/related")
    assert(str.contains("Content-Disposition: inline; filename=img01.png"),
      clue = "We should have an inlined image (visible in the HTML text)")
    assert(str.contains("Content-Disposition: attachment; filename=emil-logo.png"),
      clue = "We should have an attached logo image (visible as dedicated attachment)")
    assert(str.contains("Content-ID: <img01.png@42342343>"))
  }

  test("Write attachment without filename and declared content disposition") {
    val htmlBody = "<h1>Hello!</h1>\n<p>This <b>is</b> a mail.</p>"
    val mail: Mail[IO] = MailBuilder.build(
      From("from@example.com"),
      To("to@example.com"),
      Subject("Hello!"),
      UserAgent("my-email-client"),
      TextBody("Hello!\n\nThis is a mail."),
      HtmlBody(htmlBody),
      Attach(Attachment.textPlain[IO]("hello world!"))
        .withDisposition(Disposition.Inline)
    )

    val str = mail.serialize.unsafeRunSync()
    assert(str.nonEmpty)
    assert(str.contains("Content-Disposition: inline"))
  }

  test("Write attachment without filename and without content disposition") {
    val htmlBody = "<h1>Hello!</h1>\n<p>This <b>is</b> a mail.</p>"
    val mail: Mail[IO] = MailBuilder.build(
      From("from@example.com"),
      To("to@example.com"),
      Subject("Hello!"),
      UserAgent("my-email-client"),
      TextBody("Hello!\n\nThis is a mail."),
      HtmlBody(htmlBody),
      Attach(Attachment.textPlain[IO]("hello world!"))
    )

    val str = mail.serialize.unsafeRunSync()
    assert(str.nonEmpty)
    assert(!str.contains("Content-Disposition:"))
  }


  test("read text mail") {
    val mail = MailBuilder.build[IO](
      From("test@test.com"),
      To("test@test.com"),
      Subject("Hello"),
      TextBody("This is text")
    )

    val str = mail.serialize.unsafeRunSync()
    val mail2 = Mail
      .deserialize[IO](str)
      .unsafeRunSync()
      .copy(additionalHeaders = Headers.empty)
      .mapMailHeader(_.copy(id = "").copy(originationDate = None).copy(messageId = None))
      .mapBody(toStringContent)

    assertEquals(mail, mail2)
  }

  test("read html mail") {
    val mail = MailBuilder.build[IO](
      From("test@test.com"),
      To("test@test.com"),
      Subject("Hello"),
      HtmlBody("This is text")
    )

    val str = mail.serialize.unsafeRunSync()
    val mail2 = Mail
      .deserialize[IO](str)
      .unsafeRunSync()
      .copy(additionalHeaders = Headers.empty)
      .mapMailHeader(_.copy(id = "").copy(originationDate = None).copy(messageId = None))
      .mapBody(toStringContent)

    assertEquals(mail, mail2)
  }

  test("read alternative mail") {
    val mail = MailBuilder.build[IO](
      From("test@test.com"),
      To("test@test.com"),
      Subject("Hello"),
      TextBody("This is text"),
      HtmlBody("This is html/text")
    )

    val str = mail.serialize.unsafeRunSync()
    val mail2 = Mail
      .deserialize[IO](str)
      .unsafeRunSync()
      .copy(additionalHeaders = Headers.empty)
      .mapMailHeader(_.copy(id = "").copy(originationDate = None).copy(messageId = None))
      .mapBody(toStringContent)

    assertEquals(mail, mail2)
  }

  test("read test mail 1") {
    val url = getClass.getResource("/mails/test.eml")
    val mail = Mail.fromURL[IO](url).unsafeRunSync()
    // test decoding
    toStringContent(mail.body)
    assertEquals(mail.header.received.size, 7)
    assertEquals(mail.attachments.size, 1)
    assert(mail.body.nonEmpty)
  }

  test("read test mail 2") {
    val url = getClass.getResource("/mails/test2.eml")
    val mail = Mail.fromURL[IO](url).unsafeRunSync()
    // test decoding
    toStringContent(mail.body)
    assertEquals(mail.header.received.size, 3)
    assertEquals(mail.attachments.size, 0)
    assert(mail.body.nonEmpty)
  }

  test("read alternative mail") {
    val url = getClass.getResource("/mails/alt.eml")
    val mail = Mail.fromURL[IO](url).unsafeRunSync()
    // test decoding
    toStringContent(mail.body)
    assertEquals(mail.attachments.size, 0)
    assert(mail.body.nonEmpty)
    assert(mail.body.textPart.unsafeRunSync().isDefined)
    assert(mail.body.htmlPart.unsafeRunSync().isDefined)
    assertEquals(mail.header.received.size, 6)
  }

  test("read latin1 html mail") {
    val url = getClass.getResource("/mails/latin1-html.eml")
    val mail = Mail.fromURL[IO](url).unsafeRunSync()
    assert(mail.body.nonEmpty)
    assert(mail.body.textPart.unsafeRunSync().isEmpty)
    val htmlBody = mail.body.htmlPart.unsafeRunSync().get
    assertEquals(htmlBody.charset.get, Charset.forName("ISO-8859-15"))
    assert(htmlBody.asString.contains("Passwort-Änderung"))
  }

  test("read latin1 html mail2") {
    val url = getClass.getResource("/mails/latin1-html2.eml")
    val mail = Mail.fromURL[IO](url).unsafeRunSync()
    assert(mail.body.nonEmpty)
    assert(mail.body.htmlPart.unsafeRunSync().isEmpty)
    val textBody = mail.body.textPart.unsafeRunSync().get
    assertEquals(textBody.charset.get, Charset.forName("ISO-8859-15"))
    assert(textBody.asString.contains("Passwort-Änderung"))
  }

  test("read latin1 mail without transfer encoding") {
    val url = getClass.getResource("/mails/latin1-8bit.eml")
    val mail = Mail.fromURL[IO](url).unsafeRunSync()
    assert(mail.body.nonEmpty)
    assert(mail.body.htmlPart.unsafeRunSync().nonEmpty)
    val textBody = mail.body.htmlPart.unsafeRunSync().get
    assertEquals(textBody.charset.get, Charset.forName("ISO-8859-1"))
    assert(textBody.asString.contains("Freundliche Grüße aus Thüringen"))
  }

  test("read utf8 mail without transfer encoding") {
    val url = getClass.getResource("/mails/utf8-8bit.eml")
    val mail = Mail.fromURL[IO](url).unsafeRunSync()
    assert(mail.body.nonEmpty)
    assert(mail.body.htmlPart.unsafeRunSync().isEmpty)
    val textBody = mail.body.textPart.unsafeRunSync().get
    assertEquals(textBody.charset.get, Charset.forName("UTF-8"))
    assert(textBody.asString.contains("Passwort-Änderung"))
  }

  test("read utf8 b64 encoded filename from attachment") {
    val url = getClass.getResource("/mails/filename_b64.eml")
    val mail = Mail.fromURL[IO](url).unsafeRunSync()
    // test decoding
    toStringContent(mail.body)
    assertEquals(mail.attachments.size, 2)
    assertEquals(mail.header.subject, "Öffnung der Therapiestelle der Stiftung")
    assertEquals(
      mail.attachments.all(0).filename,
      Some("Einfache Sprache - Die Therapiestelle öffnet.pdf")
    )
    assertEquals(mail.attachments.all(1).filename, Some("Öffnung der Therapiestelle.pdf"))
  }

  test("read mail with mime tree") {
    val url = getClass.getResource("/mails/bodytree.eml")
    val mail = Mail.fromURL[IO](url).unsafeRunSync()
    // test decoding
    toStringContent(mail.body)
    assert(mail.body.nonEmpty)
    assert(mail.body.htmlPart.unsafeRunSync().isDefined)
    assert(mail.body.textPart.unsafeRunSync().isDefined)
    assertEquals(mail.attachments.size, 3)
  }

  test("read mail with content and distinguish the inlined image") {
    val url = getClass.getResource("/mails/bodytree.eml")
    val mail = Mail.fromURL[IO](url).unsafeRunSync()
    // test attachments
    assertEquals(mail.attachments.size, 3)

    // filter the single inlined attachment
    val inlined = mail.attachments.all.filter(a => a.disposition.contains(Disposition.Inline))
    assertEquals(inlined.size, 1, "Only a single attachment should be inlined")
    assertEquals(inlined(0).filename, Some("image001.png"), "The inline image should be 'image001.png'")

    val attached = mail.attachments.all.filter(a => a.disposition.contains(Disposition.Attachment))
    assertEquals(attached.size, 2, "We should have 2 real attachments")
  }

  test("read mail with nested alternative body") {
    val url = getClass.getResource("/mails/nested-alternative.eml")
    val mail = Mail.fromURL[IO](url).unsafeRunSync()
    // test decoding
    toStringContent(mail.body)
    assert(mail.body.nonEmpty)
    assert(mail.body.htmlPart.unsafeRunSync().isDefined)
    assert(mail.body.textPart.unsafeRunSync().isDefined)
    assertEquals(mail.attachments.size, 1)
  }

  test("read mail without charset declaration") {
    val url = getClass.getResource("/mails/latin1-missing-charset.eml")
    val mail = Mail.fromURL[IO](url).unsafeRunSync()
    // test decoding
    toStringContent(mail.body)
    assert(mail.body.nonEmpty)
    assert(mail.body.htmlPart.unsafeRunSync().isEmpty)
    val textBody = mail.body.textPart.unsafeRunSync().get
    assertEquals(textBody.charset, None)
    assert(textBody.asString.contains("Passwort-Änderung"))
  }

  test("read mail with empty headers") {
    val url = getClass.getResource("/mails/empty-header.eml")
    val mail = Mail.fromURL[IO](url).unsafeRunSync()
    // test decoding
    toStringContent(mail.body)
    assertEquals(mail.attachments.size, 2)
    assertEquals(mail.header.subject, "Testing")
    assertEquals(
      mail.attachments.all(0).filename,
      Some("Einfache Sprache - Die Therapiestelle öffnet.pdf")
    )
    assertEquals(mail.attachments.all(1).filename, Some("Öffnung der Therapiestelle.pdf"))
    assertEquals(
      mail.additionalHeaders.find("X-Something"),
      Some(Header("X-Something", NonEmptyList.of("")))
    )
    assertEquals(mail.header.replyTo, None)
  }

  test("read mail with empty address <>") {
    val url = getClass.getResource("/mails/empty-address.eml")
    val mail = Mail.fromURL[IO](url).unsafeRunSync()
    // test decoding
    toStringContent(mail.body)
    assertEquals(mail.attachments.size, 2)
    assertEquals(mail.header.subject, "Testing")
    assertEquals(
      mail.attachments.all(0).filename,
      Some("Einfache Sprache - Die Therapiestelle öffnet.pdf")
    )
    assertEquals(mail.attachments.all(1).filename, Some("Öffnung der Therapiestelle.pdf"))
    assertEquals(mail.header.replyTo, None)
  }

  test("read mail with invalid headers") {
    val url = getClass.getResource("/mails/broken-header.eml")
    val mail = Mail.fromURL[IO](url).unsafeRunSync()
    // test decoding
    toStringContent(mail.body)
    assertEquals(mail.attachments.size, 2)
    assertEquals(mail.header.subject, "Testing")
    assertEquals(
      mail.attachments.all(0).filename,
      Some("Einfache Sprache - Die Therapiestelle öffnet.pdf")
    )
    assertEquals(mail.attachments.all(1).filename, Some("Öffnung der Therapiestelle.pdf"))
    assertEquals(mail.header.replyTo, None)
    assertEquals(mail.header.date, None)
    assertEquals(mail.header.recipients.to, Nil)
  }
}
