package playground

import cats.implicits._
import playground.smithyql.SmithyQLParser
import playground.smithyql.WithSource
import smithy.api.Documentation
import smithy.api.ExternalDocumentation
import smithy.api.Http
import smithy4s.Service
import typings.vscode.mod

import smithyql.CompletionSchematic
import util.chaining._
import playground.smithyql.CompletionItem.Field
import playground.smithyql.CompletionItem.UnionMember
import playground.smithyql.CompletionItem.Enum
import scala.scalajs.js.JSConverters._
import playground.smithyql.OperationName

object completions {

  def complete[Alg[_[_, _, _, _, _]], Op[_, _, _, _, _]](
    service: Service[Alg, Op]
  ): (mod.TextDocument, mod.Position) => List[mod.CompletionItem] = {
    val completeOperationName = service
      .endpoints
      .map { e =>
        val getName = GetNameHint.singleton
        new mod.CompletionItem(
          s"${e.name}",
          mod.CompletionItemKind.Function,
        )
          .tap(_.insertText = e.name)
          .tap(_.detail =
            s"${e.input.compile(getName).get.value} => ${e.output.compile(getName).get.value}"
          )
          .tap(
            _.documentation = List(
              e.hints.get(Http).map { http =>
                show"HTTP ${http.method.value} ${http.uri.value} "
              },
              e.hints.get(Documentation).map(_.value),
              e.hints.get(ExternalDocumentation).map(_.value).map {
                _.map { case (k, v) => show"""${k.value}: ${v.value}""" }.mkString("\n")
              },
            ).flatten
              .map(_ + " ") // workaround for concatenation in the shortened view
              .mkString("\n\n")
          )
      }

    val completionsByEndpoint: Map[OperationName, CompletionSchematic.ResultR[Any]] =
      service
        .endpoints
        .map { endpoint =>
          OperationName(endpoint.name) -> endpoint.input.compile(new CompletionSchematic).get
        }
        .toMap

    (doc, pos) =>
      SmithyQLParser.parseFull(doc.getText()) match {
        case Left(_) if doc.getText().isBlank() => completeOperationName
        case Left(_)                            =>
          // we can try to deal with this later
          Nil
        case Right(q) =>
          val matchingNode = WithSource.atPosition(q)(adapters.fromVscodePosition(doc)(pos))

          matchingNode
            .toList
            .flatMap {
              case WithSource.NodeContext.OperationContext(_) => completeOperationName
              case WithSource.NodeContext.InputContext(ctx) =>
                val result = completionsByEndpoint(q.operationName.value).apply(ctx)

                result.map { key =>
                  key match {
                    case Field(label, tpe) =>
                      new mod.CompletionItem(label, mod.CompletionItemKind.Field)
                        // todo determine RHS based on field type
                        .tap(_.insertText = s"$label = ")
                        .tap(_.detail = tpe)

                    case Enum(label, tpe) =>
                      new mod.CompletionItem(label, mod.CompletionItemKind.EnumMember)
                        .tap(_.insertText = s"$label = ")
                        .tap(_.detail = tpe)

                    case UnionMember(label, deprecated, tpe) =>
                      new mod.CompletionItem(label, mod.CompletionItemKind.Class)
                        .tap(_.insertText = new mod.SnippetString(s"$label = {$$0},"))
                        .tap(_.detail = tpe)
                        .tap(item =>
                          if (deprecated)
                            item.tags =
                              List[mod.CompletionItemTag](
                                mod.CompletionItemTag.Deprecated
                              ).toJSArray
                          else
                            Nil
                        )
                  }
                }
            }
      }
  }

}
