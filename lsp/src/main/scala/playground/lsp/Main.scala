package playground.lsp

import cats.Show
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.implicits._
import cats.effect.kernel.Async
import cats.effect.kernel.Deferred
import cats.effect.kernel.Resource
import cats.effect.std
import cats.effect.std.Dispatcher
import cats.implicits._
import org.eclipse.lsp4j.launch.LSPLauncher
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.Authorization
import playground.TextDocumentManager
import playground.lsp.buildinfo.BuildInfo
import smithy4s.aws.AwsEnvironment
import smithy4s.aws.http4s.AwsHttp4sBackend
import smithy4s.aws.kernel.AwsRegion

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.nio.charset.Charset

object Main extends IOApp.Simple {

  private val logOut = new PrintStream(new FileOutputStream(new File("smithyql-log.txt")))
  private val logWriter = new PrintWriter(logOut)

  implicit val ioConsole: std.Console[IO] =
    new std.Console[IO] {

      def readLineWithCharset(
        charset: Charset
      ): IO[String] = IO.consoleForIO.readLineWithCharset(charset)

      def print[A](a: A)(implicit S: Show[A]): IO[Unit] = IO(logWriter.print(a.show))

      def println[A](a: A)(implicit S: Show[A]): IO[Unit] = IO(logWriter.println(a.show))

      def error[A](a: A)(implicit S: Show[A]): IO[Unit] = IO(logWriter.print("ERROR: " + a.show))

      def errorln[A](a: A)(implicit S: Show[A]): IO[Unit] = IO(
        logWriter.println("ERROR: " + a.show)
      )

    }

  def log[F[_]: std.Console](s: String): F[Unit] = std.Console[F].println(s)

  def run: IO[Unit] =
    Dispatcher[IO]
      .flatMap { implicit d =>
        std.Supervisor[IO].flatMap { implicit sup =>
          val stdin = System.in
          val stdout = System.out

          IO(System.setOut(logOut)).toResource *>
            launch(stdin, stdout)
        }
      }
      .use { launcher =>
        IO.interruptibleMany(launcher.startListening().get())
      } *> log("Server terminated without errors")

  def launch(
    in: InputStream,
    out: OutputStream,
  )(
    implicit d: Dispatcher[IO],
    sup: std.Supervisor[IO],
  ) = Deferred[IO, LanguageClient[IO]].toResource.flatMap { clientRef =>
    implicit val lc: LanguageClient[IO] = LanguageClient.defer(clientRef.get)

    makeServer[IO].evalMap { server =>
      val launcher = new LSPLauncher.Builder[PlaygroundLanguageClient]()
        .setLocalService(new PlaygroundLanguageServerAdapter(server))
        .setRemoteInterface(classOf[PlaygroundLanguageClient])
        .setInput(in)
        .setOutput(out)
        .traceMessages(logWriter)
        .create();

      log[IO]("connecting") *>
        clientRef.complete(LanguageClient.adapt[IO](launcher.getRemoteProxy())) *>
        LanguageClient[IO].showInfoMessage(s"Hello from Smithy Playground v${BuildInfo.version}") *>
        log[IO]("Server connected")
          .as(launcher)
    }
  }

  private def makeServer[F[_]: Async: std.Console](
    implicit lc: LanguageClient[F],
    sup: std.Supervisor[F],
  ): Resource[F, LanguageServer[F]] = {
    implicit val pluginResolver: PluginResolver[F] = PluginResolver.instance[F]

    EmberClientBuilder
      .default[F]
      .build
      .map(middleware.AuthorizationHeader[F])
      .flatMap { client =>
        AwsEnvironment
          .default(AwsHttp4sBackend(client), AwsRegion.US_EAST_1)
          .memoize
          .flatMap { awsEnv =>
            TextDocumentManager
              .instance[F]
              .flatMap { implicit tdm =>
                implicit val buildLoader: BuildLoader[F] = BuildLoader.instance[F]

                ServerLoader
                  .instance[F](client, awsEnv)
                  .map(_.server)

              }
              .toResource
          }
      }
  }

  private object middleware {

    def AuthorizationHeader[F[_]: Async: LanguageClient]: Client[F] => Client[F] =
      client =>
        Client[F] { request =>
          val updatedRequest =
            LanguageClient[F]
              .configuration[String]("smithyql.http.authorizationHeader")
              .flatMap {
                case v if v.trim.isEmpty() => request.pure[F]
                case v => Authorization.parse(v).liftTo[F].map(request.putHeaders(_))
              }
              .toResource

          updatedRequest
            .flatMap(client.run(_))
        }

  }

}
