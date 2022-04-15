package playground.smithyql

import schematic.Alt
import schematic.Field
import smithy4s.Hints
import smithy4s.StubSchematic
import smithy4s.internals.Hinted
import smithy4s.ShapeId
import playground.GetNameHint
import smithy4s.Endpoint
import cats.implicits._

object CompletionSchematic {
  // from context
  type ResultR[+A] = List[String] => List[CompletionItem]
  type Result[A] = Hinted[ResultR, A]
}

final case class CompletionItem(
  kind: CompletionItemKind,
  label: String,
  detail: String,
  description: Option[String],
  deprecated: Boolean,
  docs: Option[String],
)

object CompletionItem {

  def fromHintedField[F[_]](field: Field[Hinted[F, *], _, _]): CompletionItem = fromHints(
    kind = CompletionItemKind.Field,
    label = field.label,
    field.instance.hints,
  )

  def fromHintedAlt[F[_]](alt: Alt[Hinted[F, *], _, _]): CompletionItem = fromHints(
    CompletionItemKind.UnionMember,
    alt.label,
    alt.instance.hints,
  )

  def fromHints[F[_]](
    kind: CompletionItemKind,
    label: String,
    hints: Hints,
  ): CompletionItem = CompletionItem(
    kind = kind,
    label = label,
    deprecated = hints.get(smithy.api.Deprecated).isDefined,
    detail = typeAnnotationShort(hints.get(ShapeId).get),
    description = hints.get(ShapeId).get.namespace.some,
    docs = buildDocumentation(hints),
  )

  def typeAnnotationShort(shapeId: ShapeId): String = s": ${shapeId.name}"

  def forOperation[Op[_, _, _, _, _]](e: Endpoint[Op, _, _, _, _, _]) = {
    val getName = GetNameHint.singleton
    val hints = e.hints

    CompletionItem(
      kind = CompletionItemKind.Function,
      label = e.name,
      detail = s": ${e.input.compile(getName).get.value} => ${e.output.compile(getName).get.value}",
      description = none,
      deprecated = hints.get(smithy.api.Deprecated).isDefined,
      docs = buildDocumentation(hints),
    )
  }

  def buildDocumentation(hints: Hints): Option[String] = List(
    hints.get(smithy.api.Http).map { http =>
      show"HTTP ${http.method.value} ${http.uri.value} "
    },
    hints.get(smithy.api.Documentation).map(_.value),
    hints.get(smithy.api.ExternalDocumentation).map(_.value).map {
      _.map { case (k, v) => show"""${k.value}: ${v.value}""" }.mkString("\n")
    },
  ).flatten.mkString("\n\n").some.filterNot(_.isEmpty)

}

sealed trait CompletionItemKind extends Product with Serializable

object CompletionItemKind {
  case object EnumMember extends CompletionItemKind
  case object Field extends CompletionItemKind
  case object UnionMember extends CompletionItemKind
  case object Function extends CompletionItemKind
}

final class CompletionSchematic extends StubSchematic[CompletionSchematic.Result] {
  import CompletionSchematic.Result
  import CompletionSchematic.ResultR

  def default[A]: Result[A] = Hinted.static[ResultR, A](_ => Nil)

  private def retag[A, B](fs: Result[A]): Result[B] = fs.transform(identity(_): ResultR[B])

  override def enumeration[A](
    to: A => (String, Int),
    fromName: Map[String, A],
    fromOrdinal: Map[Int, A],
  ): Result[A] = Hinted[ResultR].from { hints =>
    {
      case Nil =>
        fromOrdinal.toList.sortBy(_._1).map(_._2).map { enumValue =>
          val label = to(enumValue)._1

          CompletionItem.fromHints(
            CompletionItemKind.EnumMember,
            label,
            hints,
          )
        }

      case _ =>
        // todo: this seems impossible tbh
        Nil
    }
  }

  override def map[K, V](
    fk: Result[K],
    fv: Result[V],
  ): Result[Map[K, V]] = Hinted.static[ResultR, Map[K, V]] {

    case Nil => fk.get(Nil)

    case _ =>
      // completions in map items not supported yet
      Nil

  }

  override def list[S](
    fs: Result[S]
  ): Result[List[S]] = retag(fs)

  override def vector[S](
    fs: Result[S]
  ): Result[Vector[S]] = retag(list(fs))

  override def struct[S](
    fields: Vector[Field[Result, S, _]]
  )(
    const: Vector[Any] => S
  ): Result[S] = Hinted.static[ResultR, S] {
    case Nil =>
      fields
        // todo: filter out present fields
        .sortBy(field => (field.isRequired, field.label))
        .map(CompletionItem.fromHintedField(_))
        .toList

    case h :: rest => fields.find(_.label == h).toList.flatMap(_.instance.get(rest))
  }

  override def union[S](
    first: Alt[Result, S, _],
    rest: Vector[Alt[Result, S, _]],
  )(
    total: S => Alt.WithValue[Result, S, _]
  ): Result[S] = Hinted.static[ResultR, S] {
    val all = rest.prepended(first)

    {
      case head :: tail => all.find(_.label == head).toList.flatMap(_.instance.get(tail))

      case Nil => all.map(CompletionItem.fromHintedAlt(_)).toList
    }

  }

  override def suspend[A](f: => Result[A]): Result[A] = Hinted.static[ResultR, A](in => f.get(in))

  override def bijection[A, B](f: Result[A], to: A => B, from: B => A): Result[B] = retag(f)

  override def withHints[A](fa: Result[A], hints: Hints): Result[A] = fa.addHints(hints)

}
