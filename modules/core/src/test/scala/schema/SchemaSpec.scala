// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package schema

import cats.data.{Ior, NonEmptyChain}
import cats.data.Ior.Both
import cats.tests.CatsSuite
import edu.gemini.grackle.Schema

final class SchemaSpec extends CatsSuite {
  test("schema validation: undefined types: typo in the use of a Query result type") {
    val schema =
      Schema(
      """
         type Query {
          episodeById(id: String!): Episod
         }

         type Episode {
          id: String!
        }
    """
    )

    schema match {
      case Ior.Left(e) => assert(e.head.\\("message").head.asString.get == "Reference to undefined type: Episod")
      case Both(a, b)  =>
        assert(a.head.\\("message").head.asString.get == "Reference to undefined type: Episod")
        assert(b.types.map(_.name) == List("Query", "Episode"))
      case Ior.Right(b) => fail(s"Shouldn't compile: $b")
    }
  }

  test("schema validation: undefined types: typo in the use of an InputValueDefinition") {
    val schema = Schema(
      """
         type Query {
          episodeById(id: CCid!): Episode
         }

         scalar CCId

         type Episode {
          id: CCId!
        }
    """
    )

    schema match {
      case Both(a, b)  =>
        assert(a.head.\\("message").head.asString.get == "Reference to undefined type: CCid")
        assert(b.types.map(_.name) == List("Query", "CCId", "Episode"))
      case unexpected => fail(s"This was unexpected: $unexpected")
    }
  }

  test("schema validation: multiply-defined types") {
    val schema = Schema(
      """
         type Query {
          episodeById(id: String!): Episode
         }

         type Episode {
           id: String!
         }

         type Episode {
          episodeId: String!
        }
    """
    )

    schema match {
      case Ior.Left(e) => assert(e.head.\\("message").head.asString.get == "Duplicate NamedType found: Episode")
      case unexpected => fail(s"This was unexpected: $unexpected")
    }
  }

  test("schema validation: multiple deprecated annotations") {
    val schema = Schema(
      """
         type ExampleType {
          oldField: String @deprecated @deprecated
        }
    """
    )

    schema match {
      case Ior.Left(e) => assert(e.head.\\("message").head.asString.get == "Only a single deprecated allowed at a given location")
      case unexpected => fail(s"This was unexpected: $unexpected")
    }
  }


  test("schema validation: deprecated annotation with unsupported argument") {
    val schema = Schema(
      """
         type ExampleType {
          oldField: String @deprecated(notareason: "foo bar baz")
        }
    """
    )

    schema match {
      case Ior.Left(e) => assert(e.head.\\("message").head.asString.get == "deprecated must have a single String 'reason' argument, or no arguments")
      case unexpected => fail(s"This was unexpected: $unexpected")
    }
  }

  test("schema validation: duplicate enum values") {
    val schema = Schema(
      """
         enum Direction {
          NORTH
          NORTH
        }
    """
    )

    schema match {
      case Both(a, b)  =>
        assert(a.head.\\("message").head.asString.get == "Duplicate EnumValueDefinition of NORTH for EnumTypeDefinition Direction")
        assert(b.types.map(_.name) == List("Direction"))
      case unexpected => fail(s"This was unexpected: $unexpected")
    }
  }

  test("schema validation: object implementing unspecified interfaces") {
    val schema = Schema(
      """
         type Human implements Character & Contactable {
           name: String!
         }
    """
    )

    schema match {
      case Both(a, b)  =>
        assert(a.map(_.\\("message").head.asString.get) == NonEmptyChain("Interface Character implemented by type Human is not defined", "Interface Contactable implemented by type Human is not defined"))
        assert(b.types.map(_.name) == List("Human"))
      case unexpected => fail(s"This was unexpected: $unexpected")
    }
  }

  test("schema validation: object failing to implement interface fields") {
    val schema = Schema(
      """
         interface Character {
          id: ID!
          name: String!
          email: String!
        }

         type Human implements Character {
           name: String!
         }
    """
    )

    schema match {
      case Both(a, b)  =>
        assert(a.map(_.\\("message").head.asString.get) == NonEmptyChain("Expected field id from interface Character is not implemented by type Human", "Expected field email from interface Character is not implemented by type Human"))
        assert(b.types.map(_.name) == List("Character", "Human"))
      case unexpected => fail(s"This was unexpected: $unexpected")
    }
  }

  test("schema validation: object implementing interface field with wrong type") {
    val schema = Schema(
      """
         interface Character {
          name: String!
        }

         type Human implements Character {
           name: Int!
         }
    """
    )

    schema match {
      case Both(a, b)  =>
        assert(a.map(_.\\("message").head.asString.get) == NonEmptyChain("Expected field name from interface Character is not implemented by type Human"))
        assert(b.types.map(_.name) == List("Character", "Human"))
      case unexpected => fail(s"This was unexpected: $unexpected")
    }
  }

  test("schema validation: multiple objects failing to implement interface field") {
    val schema = Schema(
      """
         interface Character {
          id: ID!
          name: String!
        }

         type Human implements Character {
           name: String!
         }

         type Dog implements Character {
           name: String!
         }
    """
    )

    schema match {
      case Both(a, b)  =>
        assert(a.map(_.\\("message").head.asString.get) == NonEmptyChain("Expected field id from interface Character is not implemented by type Human", "Expected field id from interface Character is not implemented by type Dog"))
        assert(b.types.map(_.name) == List("Character", "Human", "Dog"))
      case unexpected => fail(s"This was unexpected: $unexpected")
    }
  }

  test("schema validation: object failing to implement multiple interface fields") {
    val schema = Schema(
      """
         interface Character {
          id: ID!
          name: String!
        }

        interface Contactable {
          email: String!
        }

         type Human implements Character & Contactable {
           name: String!
         }
    """
    )

    schema match {
      case Both(a, b)  =>
        assert(a.map(_.\\("message").head.asString.get) == NonEmptyChain("Expected field id from interface Character is not implemented by type Human", "Expected field email from interface Contactable is not implemented by type Human"))
        assert(b.types.map(_.name) == List("Character", "Contactable", "Human"))
      case unexpected => fail(s"This was unexpected: $unexpected")
    }
  }

  //FIXME reformat examples
  test("schema validation: object correctly implements transitive interface") {
    val schema = Schema(
      """
         interface Node {
            id: ID!
          }

          interface Resource implements Node {
            id: ID!
            url: String
          }

         type Human implements Resource & Node {
            id: ID!
            url: String
         }
    """
    )

    schema match {
      case Ior.Right(a) => assert(a.types.map(_.name) == List("Node", "Resource", "Human"))
      case unexpected => fail(s"This was unexpected: $unexpected")
    }
  }

  //FIXME what about when names the same but types differ? arg types differ?


  //Out of scope
  //FIXME add empty interfaces test, another PR?
  //FIXME check that object implements transative interface
}
