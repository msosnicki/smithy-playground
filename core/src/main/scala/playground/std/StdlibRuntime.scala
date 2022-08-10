package playground.std

import cats.Functor
import cats.effect.std.UUIDGen
import cats.implicits._
import smithy4s.Timestamp

trait StdlibRuntime[F[_]] {
  def random: Random[F]
  def clock: Clock[F]
}

object StdlibRuntime {
  def apply[F[_]](implicit F: StdlibRuntime[F]): StdlibRuntime[F] = F

  def instance[F[_]: cats.effect.Clock: UUIDGen: Functor]: StdlibRuntime[F] =
    new StdlibRuntime[F] {

      val random: Random[F] =
        new playground.std.Random[F] {
          def nextUUID(): F[NextUUIDOutput] = UUIDGen[F].randomUUID.map(NextUUIDOutput(_))
        }

      def clock: Clock[F] =
        new Clock[F] {

          def currentTimestamp(
          ): F[CurrentTimestampOutput] = cats
            .effect
            .Clock[F]
            .realTimeInstant
            .map(Timestamp.fromInstant)
            .map(CurrentTimestampOutput.apply)

        }

    }

}
