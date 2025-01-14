package simple_trees

class OverloadedApply {

  object Foo {
    object Bar
  }

  class Box[T]

  class Num

  def foo(a: Int): Unit = ()
  def foo(a: Box[Num]): Unit = ()
  def foo(a: Foo.Bar.type): Unit = ()
  // def foo(a: Array[Foo.type]): Unit = () // TODO: re-enable when we add types to scala 2 symbols
  def foo(a: => Num): Unit = ()

  def callA = foo(1)
  def callB = foo(Box())
  def callC = foo(Foo.Bar)
  // def callD = foo(Array(Foo)) // TODO: re-enable when we add types to scala 2 symbols
  def callE = foo(Num())

}
