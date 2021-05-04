// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.grackle

import io.circe._
import io.circe.syntax._

/** A problem, to be reported back to the user. */
final case class Problem(
  message: String,
  locations: List[(Int, Int)] = Nil,
  path: List[String] = Nil
) {

  override def toString = {

    lazy val pathText: String =
      path.mkString("/")

    lazy val locationsText: String =
      locations.map { case (a, b) =>
        if (a == b) a.toString else s"$a..$b"
      } .mkString(", ")

    (path.nonEmpty, locations.nonEmpty) match {
      case (true, true)   => s"$message (at $pathText: $locationsText)"
      case (true, false)  => s"$message (at $pathText)"
      case (false, true)  => s"$message (at $locationsText)"
      case (false, false) => message
    }

  }

}

object Problem {

  implicit val ProblemEncoder: Encoder[Problem] = { p =>

    val locationsField: List[(String, Json)] =
      if (p.locations.isEmpty) Nil
      else List(
        "locations" ->
          p.locations.map { case (line, col) =>
            Json.obj(
              "line" -> line.asJson,
              "col"  -> col.asJson
            )
          } .asJson
      )

    val pathField: List[(String, Json)] =
      if (p.path.isEmpty) Nil
      else List(("path" -> p.path.asJson))

    Json.fromFields(
      "message" -> p.message.asJson ::
      locationsField                :::
      pathField
    )

  }

}