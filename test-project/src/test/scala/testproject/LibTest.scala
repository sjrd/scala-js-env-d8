package testproject

import scala.scalajs.js

import org.junit.Test
import org.junit.Assert._

class LibTest {
  @Test def square(): Unit = {
    assertEquals(36, Lib.square(6))
  }
}
