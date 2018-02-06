package testproject

object TestApp {

  def main(args: Array[String]): Unit = {
    val x = 6
    println(s"$x² = ${Lib.square(x)}")
  }

}
