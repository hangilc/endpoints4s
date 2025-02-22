package endpoints4s.http4s.client

import endpoints4s.algebra
import endpoints4s.algebra.client

import cats.effect.Concurrent
import org.http4s.client.Client
import cats.effect.IO
import scala.concurrent.Future
import _root_.org.http4s.asynchttpclient.client.AsyncHttpClient
import cats.effect.unsafe.implicits.global
import endpoints4s.algebra.circe
import org.http4s.Uri

class TestJsonSchemaClient[F[_]: Concurrent](host: Uri, client: Client[F])
    extends Endpoints[F](host, client)
    with BasicAuthentication
    with JsonEntitiesFromCodecs
    with algebra.BasicAuthenticationTestApi
    with algebra.EndpointsTestApi
    with algebra.JsonFromCodecTestApi
    with algebra.SumTypedEntitiesTestApi
    with circe.JsonFromCirceCodecTestApi
    with circe.JsonEntitiesFromCodecs

class Http4sClientEndpointsJsonSchemaTest
    extends client.EndpointsTestSuite[TestJsonSchemaClient[IO]]
    with client.BasicAuthTestSuite[TestJsonSchemaClient[IO]]
    with client.JsonFromCodecTestSuite[TestJsonSchemaClient[IO]]
    with client.SumTypedEntitiesTestSuite[TestJsonSchemaClient[IO]] {

  val (ahc, shutdown) =
    AsyncHttpClient.allocate[IO]().unsafeRunSync()

  val client = new TestJsonSchemaClient[IO](
    Uri.unsafeFromString(s"http://localhost:$wiremockPort"),
    ahc
  )

  def call[Req, Resp](
      endpoint: client.Endpoint[Req, Resp],
      args: Req
  ): Future[Resp] = {
    Thread.sleep(50)
    val eventualResponse = endpoint(args)
    Thread.sleep(50)
    eventualResponse.unsafeToFuture()
  }

  def encodeUrl[A](url: client.Url[A])(a: A): String =
    url.encodeUrl(a).toOption.get.renderString

  clientTestSuite()
  basicAuthSuite()
  jsonFromCodecTestSuite()

  override def afterAll(): Unit = {
    shutdown.unsafeRunSync()
    super.afterAll()
  }

}
