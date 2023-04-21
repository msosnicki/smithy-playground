package playground

import cats.implicits._
import cats.kernel.Eq
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

final case class PlaygroundConfig(
  imports: List[String],
  dependencies: List[String],
  repositories: List[String],
  extensions: List[String],
)

object PlaygroundConfig {

  implicit val eq: Eq[PlaygroundConfig] = Eq.fromUniversalEquals

  val empty: PlaygroundConfig = PlaygroundConfig(
    imports = Nil,
    dependencies = Nil,
    repositories = Nil,
    extensions = Nil,
  )

  private object internal {

    final case class BuildConfig(
      mavenDependencies: List[String] = Nil,
      mavenRepositories: List[String] = Nil,
      imports: List[String] = Nil,
      maven: Option[MavenConfig] = None,
      smithyPlayground: Option[SmithyPlaygroundPluginConfig] = None,
    ) {

      def toPlaygroundConfig: PlaygroundConfig = PlaygroundConfig(
        imports = imports,
        dependencies = mavenDependencies ++ maven.foldMap(_.dependencies),
        repositories = mavenRepositories ++ maven.foldMap(_.repositories).map(_.url),
        extensions = smithyPlayground.foldMap(_.extensions),
      )

    }

    object BuildConfig {
      implicit val c: JsonValueCodec[BuildConfig] = JsonCodecMaker.make[BuildConfig]

      def fromPlaygroundConfig(
        c: PlaygroundConfig
      ): BuildConfig = BuildConfig(
        mavenDependencies = c.dependencies,
        mavenRepositories = c.repositories,
        imports = c.imports,
        smithyPlayground = c.extensions.toNel.map { e =>
          SmithyPlaygroundPluginConfig(extensions = e.toList)
        },
      )

    }

    final case class MavenConfig(
      dependencies: List[String] = Nil,
      repositories: List[Repository] = Nil,
    )

    final case class Repository(
      url: String
    )

    final case class SmithyPlaygroundPluginConfig(
      extensions: List[String] = Nil
    )

  }

  import com.github.plokhotnyuk.jsoniter_scala.core._

  val decode: Array[Byte] => Either[Throwable, PlaygroundConfig] =
    bytes =>
      Either.catchNonFatal(readFromArray[internal.BuildConfig](bytes)).map(_.toPlaygroundConfig)

  val encode: PlaygroundConfig => Array[Byte] = internal
    .BuildConfig
    .fromPlaygroundConfig
    .andThen(writeToArray[internal.BuildConfig](_))

}
