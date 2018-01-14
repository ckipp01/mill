package mill.main

import mill.define._
import mill.define.TaskModule
import ammonite.main.Router

object Resolve {
  def resolve[T, V](remainingSelector: List[Segment],
                    obj: mill.Module,
                    rest: Seq[String],
                    remainingCrossSelectors: List[List[String]],
                    revSelectorsSoFar: List[Segment]): Either[String, Task[Any]] = {

    remainingSelector match{
      case Segment.Cross(_) :: Nil => Left("Selector cannot start with a [cross] segment")
      case Segment.Label(last) :: Nil =>
        def target =
          obj
            .reflect[Target[_]]
            .find(_.label == last)
            .map(Right(_))

        def invokeCommand[V](target: mill.Module, name: String) = for{
          cmd <- target.commands.find(_.name == name)
        } yield cmd.invoke(target, ammonite.main.Scripts.groupArgs(rest.toList)) match {
          case Router.Result.Success(v) => Right(v)
          case _ => Left(s"Command failed $last")
        }

        def runDefault = for{
          child <- obj.reflect[mill.Module]
          if child.ctx.segment == Segment.Label(last)
          res <- child match{
            case taskMod: TaskModule => Some(invokeCommand(child, taskMod.defaultCommandName()))
            case _ => None
          }
        } yield res

        def command = invokeCommand(obj, last)

        command orElse target orElse runDefault.headOption.flatten match{
          case None =>  Left("Cannot resolve task " +
            Segments((Segment.Label(last) :: revSelectorsSoFar).reverse:_*).render
          )
          // Contents of `either` *must* be a `Task`, because we only select
          // methods returning `Task` in the discovery process
          case Some(either) => either.right.map{ case x: Task[Any] => x }
        }


      case head :: tail =>
        val newRevSelectorsSoFar = head :: revSelectorsSoFar
        head match{
          case Segment.Label(singleLabel) =>
            obj.reflect[mill.Module].find{
              _.ctx.segment == Segment.Label(singleLabel)
            } match{
              case Some(child: mill.Module) => resolve(tail, child, rest, remainingCrossSelectors, newRevSelectorsSoFar)
              case None => Left("Cannot resolve module " + Segments(newRevSelectorsSoFar.reverse:_*).render)
            }

          case Segment.Cross(cross) =>
            obj match{
              case c: Cross[_] =>
                c.itemMap.get(cross.toList) match{
                  case Some(m: mill.Module) => resolve(tail, m, rest, remainingCrossSelectors, newRevSelectorsSoFar)
                  case None => Left("Cannot resolve cross " + Segments(newRevSelectorsSoFar.reverse:_*).render)

                }
              case _ => Left("Cannot resolve cross " + Segments(newRevSelectorsSoFar.reverse:_*).render)
            }
        }

      case Nil => Left("Selector cannot be empty")
    }
  }
}
