// Copyright (c) 2016-2019 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.grackle

import cats.Monad
import cats.implicits._
import io.circe.Json
import io.circe.literal.JsonStringContext

sealed trait Query {
  import Query._

  def ~(query: Query): Query = (this, query) match {
    case (Group(hd), Group(tl)) => Group(hd ++ tl)
    case (hd, Group(tl)) => Group(hd :: tl)
    case (Group(hd), tl) => Group(hd :+ tl)
    case (hd, tl) => Group(List(hd, tl))
  }
}

object Query {
  case class Select(name: String, args: List[Binding], child: Query = Empty) extends Query
  case class Group(queries: List[Query]) extends Query
  case object Empty extends Query

  sealed trait Binding {
    def name: String
    type T
    val value: T
  }
  object Binding {
    case class StringBinding(name: String, value: String) extends Binding { type T = String }

    def toMap(bindings: List[Binding]): Map[String, Any] =
      bindings.map(b => (b.name, b.value)).toMap
  }
}

sealed trait ProtoJson {
  import ProtoJson._
  import QueryInterpreter.mkError

  def complete[F[_]: Monad](mapping: ComponentMapping[F]): F[Result[Json]] =
    this match {
      case PureJson(value) => value.rightIor.pure[F]

      case DeferredJson(cursor, tpe, fieldName, query) =>
        mapping.subobject(tpe, fieldName) match {
          case Some(mapping.Subobject(submapping, subquery)) =>
            subquery(cursor, query).flatTraverse(submapping.interpreter.runRootValue).flatMap(_.flatTraverse(_.complete(mapping)))
          case _ => List(mkError(s"failed: $tpe $fieldName $query")).leftIor.pure[F]
        }

      case ProtoObject(fields) =>
        (fields.traverse { case (name, value) => value.complete(mapping).nested.map(v => (name, v)) }).map(Json.fromFields).value

      case ProtoArray(elems) =>
        elems.traverse(value => value.complete(mapping).nested).map(Json.fromValues).value
    }
}

object ProtoJson {
  case class PureJson(value: Json) extends ProtoJson
  case class DeferredJson(cursor: Cursor, tpe: Type, fieldName: String, query: Query) extends ProtoJson
  case class ProtoObject(fields: List[(String, ProtoJson)]) extends ProtoJson
  case class ProtoArray(elems: List[ProtoJson]) extends ProtoJson

  def deferred(cursor: Cursor, tpe: Type, fieldName: String, query: Query): ProtoJson = DeferredJson(cursor, tpe, fieldName, query)

  def fromJson(value: Json): ProtoJson = PureJson(value)

  def fromFields(fields: List[(String, ProtoJson)]): ProtoJson =
    if(fields.forall(_._2.isInstanceOf[PureJson]))
      PureJson(Json.fromFields(fields.map { case (name, c) => (name, c.asInstanceOf[PureJson].value) }))
    else
      ProtoObject(fields)

  def fromValues(elems: List[ProtoJson]): ProtoJson =
    if(elems.forall(_.isInstanceOf[PureJson]))
      PureJson(Json.fromValues(elems.map(_.asInstanceOf[PureJson].value)))
    else
      ProtoArray(elems)
}

abstract class QueryInterpreter[F[_]](implicit val F: Monad[F]) {
  import Query._
  import QueryInterpreter.mkError
  import ComponentMapping.NoMapping

  val schema: Schema

  def run(query: Query, mapping: ComponentMapping[F] = NoMapping): F[Json] =
    runRoot(query, mapping).map(QueryInterpreter.mkResponse)

  def runRoot(query: Query, mapping: ComponentMapping[F] = NoMapping): F[Result[Json]] =
    query match {
      case Select(fieldName, _, _) =>
        runRootValue(query).flatMap(_.flatTraverse(_.complete(mapping))).nested.map(value => Json.obj((fieldName, value))).value
      case _ =>
        List(mkError(s"Bad query: $query")).leftIor.pure[F]
    }

  def runRootValue(query: Query): F[Result[ProtoJson]]

  def runFields(query: Query, tpe: Type, cursor: Cursor): F[Result[List[(String, ProtoJson)]]] = {
    (query, tpe) match {
      case (sel@Select(fieldName, _, _), NullableType(tpe)) =>
        cursor.asNullable.flatTraverse(oc =>
          oc.map(c => runFields(sel, tpe, c)).getOrElse(List((fieldName, ProtoJson.fromJson(Json.Null))).rightIor.pure[F])
        )

      case (Select(fieldName, bindings, child), tpe) =>
        if (!cursor.hasField(fieldName)) List((fieldName, ProtoJson.deferred(cursor, tpe, fieldName, child))).rightIor.pure[F]
        else
          cursor.field(fieldName, Binding.toMap(bindings)).flatTraverse(c =>
            runValue(child, tpe.field(fieldName), c).nested.map(value => List((fieldName, value))).value
          )

      case (Group(siblings), _) =>
        siblings.flatTraverse(query => runFields(query, tpe, cursor).nested).value

      case _ =>
        List(mkError(s"failed: $query $tpe")).leftIor.pure[F]
    }
  }

  def runValue(query: Query, tpe: Type, cursor: Cursor): F[Result[ProtoJson]] = {
    tpe match {
      case NullableType(tpe) =>
        cursor.asNullable.flatTraverse(oc =>
          oc.map(c => runValue(query, tpe, c)).getOrElse(ProtoJson.fromJson(Json.Null).rightIor.pure[F])
        )

      case ListType(tpe) =>
        cursor.asList.flatTraverse(lc =>
          lc.traverse(c => runValue(query, tpe, c).nested).map(ProtoJson.fromValues).value
        )

      case TypeRef(schema, tpnme) =>
        schema.types.find(_.name == tpnme)
          .map(tpe => runValue(query, tpe, cursor))
          .getOrElse(List(mkError(s"Unknown type '$tpnme'")).leftIor.pure[F])

      case (_: ScalarType) | (_: EnumType) => cursor.asLeaf.map(ProtoJson.fromJson).pure[F]

      case (_: ObjectType) | (_: InterfaceType) =>
        runFields(query, tpe, cursor).nested.map(ProtoJson.fromFields).value

      case _ =>
        Thread.dumpStack
        List(mkError(s"Unsupported type $tpe")).leftIor.pure[F]
    }
  }
}

object QueryInterpreter {
  def mkResponse(data: Option[Json], errors: List[Json] = Nil): Json = {
    val dataField = data.map { value => ("data", value) }.toList
    val errorField = if (errors.isEmpty) Nil else List(("errors", Json.fromValues(errors)))
    Json.fromFields(errorField ++ dataField)
  }

  def mkResponse(result: Result[Json]): Json =
    mkResponse(result.right, result.left.getOrElse(Nil))

  def mkError(message: String, locations: List[(Int, Int)] = Nil, path: List[String] = Nil): Json = {
    val locationsField =
      if (locations.isEmpty) Nil
      else
        List((
          "locations",
          Json.fromValues(locations.map { case (line, col) => json""" { "line": $line, "col": $col } """ })
        ))
    val pathField =
      if (path.isEmpty) Nil
      else List(("path", Json.fromValues(path.map(Json.fromString))))

    Json.fromFields(("message", Json.fromString(message)) :: locationsField ++ pathField)
  }
}

trait ComponentMapping[F[_]] {
  val objectMappings: List[ObjectMapping]

  def subobject(tpe: Type, fieldName: String): Option[Subobject] =
    objectMappings.find(_.tpe =:= tpe) match {
      case Some(om) =>
        om.fieldMappings.find(_._1 == fieldName) match {
          case Some((_, so: Subobject)) => Some(so)
          case _ => None
        }
      case None => None
    }

  sealed trait FieldMapping

  case class ObjectMapping(
    tpe: Type,
    interpreter: QueryInterpreter[F],
    fieldMappings: List[(String, FieldMapping)]
  )

  val defaultJoin: (Cursor, Query) => Result[Query] = (_, subquery: Query) => subquery.rightIor

  case class Subobject(
    submapping: ObjectMapping,
    subquery: (Cursor, Query) => Result[Query] = defaultJoin
  ) extends FieldMapping
}

object ComponentMapping {
  def NoMapping[F[_]]: ComponentMapping[F] =
    new ComponentMapping[F] {
      val objectMappings = Nil
    }
}

trait ComposedQueryInterpreter[F[_]] extends QueryInterpreter[F] with ComponentMapping[F] {
  import Query._
  import QueryInterpreter.mkError

  def run(query: Query): F[Json] = run(query, this)

  def runRootValue(query: Query): F[Result[ProtoJson]] = {
    query match {
      case Select(fieldName, _, _) =>
        subobject(schema.queryType, fieldName) match {
          case Some(Subobject(submapping, _)) => submapping.interpreter.runRootValue(query)
          case None => List(mkError("Bad query")).leftIor.pure[F]
        }
      case _ => List(mkError("Bad query")).leftIor.pure[F]
    }
  }
}
