package simple_trees

trait Trait

class RefinedType {
  def returnsRefined[T]: TypeMember =
    new TypeMember {
      override type AbstractType       = T
      override type AbstractWithBounds = Null
    }

  def returnsTrait: Trait =
    new Trait {
      def f: Int = 0
    }
}
