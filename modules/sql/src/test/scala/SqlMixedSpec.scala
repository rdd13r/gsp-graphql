// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.grackle.sql.test

import cats.effect.IO
import io.circe.Json
import org.scalatest.funsuite.AnyFunSuite
import cats.effect.unsafe.implicits.global

import edu.gemini.grackle._
import syntax._

import grackle.test.GraphQLResponseTests.assertWeaklyEqual

trait SqlMixedSpec extends AnyFunSuite {
  def mapping: QueryExecutor[IO, Json]

  test("DB query") {
    val query = """
      query {
        movie(id: "6a7837fc-b463-4d32-b628-0f4b3065cb21") {
          title
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "movie" : {
            "title" : "Celine et Julie Vont en Bateau"
          }
        }
      }
    """

    val res = mapping.compileAndRun(query).unsafeRunSync()
    //println(res)

    assertWeaklyEqual(res, expected)
  }

  test("value query") {
    val query = """
      query {
        foo {
          value
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "foo" : {
            "value" : 23
          }
        }
      }
    """

    val res = mapping.compileAndRun(query).unsafeRunSync()
    //println(res)

    assertWeaklyEqual(res, expected)
  }

  test("circe query") {
    val query = """
      query {
        bar {
          message
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "bar" : {
            "message" : "Hello world"
          }
        }
      }
    """

    val res = mapping.compileAndRun(query).unsafeRunSync()
    //println(res)

    assertWeaklyEqual(res, expected)
  }

  test("mixed query") {
    val query = """
      query {
        movie(id: "6a7837fc-b463-4d32-b628-0f4b3065cb21") {
          title
        }
        foo {
          value
        }
        bar {
          message
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "movie" : {
            "title" : "Celine et Julie Vont en Bateau"
          },
          "foo" : {
            "value" : 23
          },
          "bar" : {
            "message" : "Hello world"
          }
        }
      }
    """

    val res = mapping.compileAndRun(query).unsafeRunSync()
    //println(res)

    assertWeaklyEqual(res, expected)
  }
}
