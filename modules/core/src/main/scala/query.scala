// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.grackle

import scala.annotation.tailrec

import cats.kernel.Order

import Cursor.Env
import Query._

/** GraphQL query Algebra */
sealed trait Query {
  /** Groups this query with its argument, Groups on either side are merged */
  def ~(query: Query): Query = (this, query) match {
    case (Group(hd), Group(tl)) => Group(hd ++ tl)
    case (hd, Group(tl)) => Group(hd :: tl)
    case (Group(hd), tl) => Group(hd :+ tl)
    case (hd, tl) => Group(List(hd, tl))
  }

  /** Yields a String representation of this query */
  def render: String
}

object Query {
  /** Select field `name` given arguments `args` and continue with `child` */
  case class Select(name: String, args: List[Binding], child: Query = Empty) extends Query {
    def eliminateArgs(elim: Query => Query): Query = copy(args = Nil, child = elim(child))

    def render = {
      val rargs = if(args.isEmpty) "" else s"(${args.map(_.render).mkString(", ")})"
      val rchild = if(child == Empty) "" else s" { ${child.render} }"
      s"$name$rargs$rchild"
    }
  }

  /** A Group of sibling queries at the same level */
  case class Group(queries: List[Query]) extends Query {
    def render = queries.map(_.render).mkString("{", ", ", "}")
  }

  /** A Group of sibling queries as a list */
  case class GroupList(queries: List[Query]) extends Query {
    def render = queries.map(_.render).mkString("[", ", ", "]")
  }

  /** Continues with single-element-list-producing `child` and yields the single element */
  case class Unique(child: Query) extends Query {
    def render = s"<unique: ${child.render}>"
  }

  /** Retains only elements satisfying `pred` and continues with `child` */
  case class Filter(pred: Predicate, child: Query) extends Query {
    def render = s"<filter: $pred ${child.render}>"
  }

  /** Identifies a component boundary.
   *  `join` is applied to the current cursor and `child` yielding a continuation query which will be
   *  evaluated by the interpreter identified by `componentId`.
   */
  case class Component[F[_]](mapping: Mapping[F], join: (Cursor, Query) => Result[Query], child: Query) extends Query {
    def render = s"<component: $mapping ${child.render}>"
  }

  /** Evaluates an introspection query relative to `schema` */
  case class Introspect(schema: Schema, child: Query) extends Query {
    def render = s"<introspect: ${child.render}>"
  }

  /** A deferred query.
   *  `join` is applied to the current cursor and `child` yielding a continuation query which will be
   *  evaluated by the current interpreter in its next stage.
   */
  case class Defer(join: (Cursor, Query) => Result[Query], child: Query, rootTpe: Type) extends Query {
    def render = s"<defer: ${child.render}>"
  }

  /** Add `env` to the environment for the continuation `child` */
  case class Environment(env: Env, child: Query) extends Query {
    def render = s"<environment: $env ${child.render}>"
  }

  /**
   * Wraps the result of `child` as a field named `name` of an enclosing object.
   */
  case class Wrap(name: String, child: Query) extends Query {
    def render = {
      val rchild = if(child == Empty) "" else s" { ${child.render} }"
      s"$name$rchild"
    }
  }

  /**
   * Rename the topmost field of `sel` to `name`.
   */
  case class Rename(name: String, child: Query) extends Query {
    def render = s"<rename: $name ${child.render}>"
  }

  /**
   * Untyped precursor of `Narrow`.
   *
   * Trees of this type will be replaced by a corresponding `Narrow` by
   * `SelectElaborator`.
   */
  case class UntypedNarrow(tpnme: String, child: Query) extends Query {
    def render = s"<narrow: $tpnme ${child.render}>"
  }

  /**
   * The result of `child` if the focus is of type `subtpe`, `Empty` otherwise.
   */
  case class Narrow(subtpe: TypeRef, child: Query) extends Query {
    def render = s"<narrow: $subtpe ${child.render}>"
  }

  /** Skips/includes the continuation `child` depending on the value of `cond` */
  case class Skip(sense: Boolean, cond: Value, child: Query) extends Query {
    def render = s"<skip: $sense $cond ${child.render}>"
  }

  /** Limits the results of list-producing continuation `child` to `num` elements */
  case class Limit(num: Int, child: Query) extends Query {
    def render = s"<limit: $num ${child.render}>"
  }

  /** Drops the first `num` elements of list-producing continuation `child`. */
  case class Offset(num: Int, child: Query) extends Query {
    def render = s"<offset: $num ${child.render}>"
  }

  /** Orders the results of list-producing continuation `child` by fields
   *  specified by `selections`.
   */
  case class OrderBy(selections: OrderSelections, child: Query) extends Query {
    def render = s"<order-by: $selections ${child.render}>"
  }

  case class OrderSelections(selections: List[OrderSelection[_]]) {
    def order(lc: List[Cursor]): List[Cursor] = {
      def cmp(x: Cursor, y: Cursor): Int = {
        @tailrec
        def loop(sels: List[OrderSelection[_]]): Int =
          sels match {
            case Nil => 0
            case hd :: tl =>
              hd(x, y) match {
                case 0 => loop(tl)
                case ord => ord
              }
          }

        loop(selections)
      }

      lc.sortWith((x, y) => cmp(x, y) < 0)
    }
  }

  case class OrderSelection[T: Order](term: Term[T], ascending: Boolean = true, nullsLast: Boolean = true) {
    def apply(x: Cursor, y: Cursor): Int = {
      def deref(c: Cursor): Option[T] =
        if (c.isNullable) c.asNullable.getOrElse(None).flatMap(term(_).toOption)
        else term(c).toOption

      (deref(x), deref(y)) match {
        case (None, None) => 0
        case (_, None) => (if (nullsLast) -1 else 1)
        case (None, _) => (if (nullsLast) 1 else -1)
        case (Some(x0), Some(y0)) =>
          val ord = Order[T].compare(x0, y0)
          if (ascending) ord
          else -ord
      }
    }
  }

  /** Computes the number of top-level elements of `child` as field `name` */
  case class Count(name: String, child: Query) extends Query {
    def render = s"$name:count { ${child.render} }"
   }

  /** A placeholder for a skipped node */
  case object Skipped extends Query {
    def render = "<skipped>"
  }

  /** The terminal query */
  case object Empty extends Query {
    def render = ""
  }

  case class Binding(name: String, value: Value) {
    def render: String = s"$name: $value"
  }

  type UntypedVarDefs = List[UntypedVarDef]
  type VarDefs = List[InputValue]
  type Vars = Map[String, (Type, Value)]

  case class UntypedVarDef(name: String, tpe: Ast.Type, default: Option[Value])

  /** Extractor for nested Rename/Select patterns in the query algebra */
  object PossiblyRenamedSelect {
    def apply(sel: Select, resultName: String): Query = sel match {
      case Select(`resultName`, _, _) => sel
      case _ => Rename(resultName, sel)
    }

    def apply(sel: Select, resultName: Option[String]): Query = resultName match {
      case Some(resultName) => Rename(resultName, sel)
      case None => sel
    }

    def unapply(q: Query): Option[(Select, String)] =
      q match {
        case Rename(name, sel: Select) => Some((sel, name))
        case sel: Select => Some((sel, sel.name))
        case _ => None
      }
  }

  def renameRoot(q: Query, rootName: String): Option[Query] = q match {
    case Rename(_, sel@Select(`rootName`, _, _)) => Some(sel)
    case r@Rename(`rootName`, _)                 => Some(r)
    case Rename(_, sel: Select)                  => Some(Rename(rootName, sel))
    case sel@Select(`rootName`, _, _)            => Some(sel)
    case sel: Select                             => Some(Rename(rootName, sel))
    case w@Wrap(`rootName`, _)                   => Some(w)
    case w: Wrap                                 => Some(w.copy(name = rootName))
    case _ => None
  }

  def rootName(q: Query): Option[String] = q match {
    case Select(name, _, _)       => Some(name)
    case Wrap(name, _)            => Some(name)
    case Rename(name, _)          => Some(name)
    case _                        => None
  }

  /** Extractor for nested Filter/OrderBy/Limit patterns in the query algebra */
  object FilterOrderByLimit {
    def unapply(q: Query): Option[(Option[Predicate], Option[List[OrderSelection[_]]], Option[Int],  Option[Int], Query)] = {
      val (limit, q0) = q match {
        case Limit(lim, child) => (Some(lim), child)
        case child => (None, child)
      }
      val (offset, q1) = q0 match {
        case Offset(off, child) => (Some(off), child)
        case child => (None, child)
      }
      val (order, q2) = q1 match {
        case OrderBy(OrderSelections(oss), child) => (Some(oss), child)
        case child => (None, child)
      }
      val (filter, q3) = q2 match {
        case Filter(pred, child) => (Some(pred), child)
        case child => (None, child)
      }
      limit.orElse(order).orElse(filter).map { _ =>
        (filter, order, offset, limit, q3)
      }
    }
  }

  /** Construct a query which yields all the supplied paths */
  def mkPathQuery(paths: List[List[String]]): List[Query] =
    paths match {
      case Nil => Nil
      case paths =>
        val oneElemPaths = paths.filter(_.sizeCompare(1) == 0).distinct
        val oneElemQueries: List[Query] = oneElemPaths.map(p => Select(p.head, Nil, Empty))
        val multiElemPaths = paths.filter(_.length > 1).distinct
        val grouped: List[Query] = multiElemPaths.groupBy(_.head).toList.map {
          case (fieldName, suffixes) =>
            Select(fieldName, Nil, mergeQueries(mkPathQuery(suffixes.map(_.tail).filterNot(_.isEmpty))))
        }
        oneElemQueries ++ grouped
    }

  /** Merge the given queries as a single query */
  def mergeQueries(qs: List[Query]): Query = {
    qs.filterNot(_ == Empty) match {
      case Nil => Empty
      case List(one) => one
      case qs =>
        def flattenLevel(qs: List[Query]): List[Query] = {
          def loop(qs: List[Query], acc: List[Query]): List[Query] =
            qs match {
              case Nil => acc.reverse
              case Group(gs) :: tl => loop(gs ++ tl, acc)
              case Empty :: tl => loop(tl, acc)
              case hd :: tl => loop(tl, hd :: acc)
            }
          loop(qs, Nil)
        }

        val flattened = flattenLevel(qs)
        val (selects, rest) = flattened.partition { case PossiblyRenamedSelect(_, _) => true ; case _ => false }

        val mergedSelects =
          selects.groupBy { case PossiblyRenamedSelect(Select(fieldName, _, _), resultName) => (fieldName, resultName) ; case _ => sys.error("Impossible") }.values.map { rsels =>
            val PossiblyRenamedSelect(Select(fieldName, _, _), resultName) = rsels.head
            val sels = rsels.map { case PossiblyRenamedSelect(sel, _) => sel ; case _ => sys.error("Impossible") }
            val children = sels.map(_.child)
            val merged = mergeQueries(children)
            PossiblyRenamedSelect(Select(fieldName, Nil, merged), resultName)
          }

        Group(rest ++ mergedSelects)
      }
  }
}
