package tastyquery.ast

import tastyquery.ast.Names.*
import tastyquery.ast.Symbols.RegularSymbol
import tastyquery.ast.Trees.{DefTree, Tree, TypeParam}
import tastyquery.ast.Types.*
import tastyquery.ast.Spans.{Span, NoSpan}
import tastyquery.util.syntax.chaining.given
import tastyquery.Contexts.Context

object TypeTrees {
  class TypeTreeToTypeError(val typeTree: TypeTree) extends RuntimeException(s"Could not convert $typeTree to type")

  object TypeTreeToTypeError {
    def unapply(e: TypeTreeToTypeError): Option[TypeTree] = Some(e.typeTree)
  }

  abstract class TypeTree(val span: Span) {
    private var myType: Type | Null = null

    protected def calculateType(using Context): Type

    def withSpan(span: Span): TypeTree

    final def toType(using Context): Type = {
      val local = myType
      if local == null then calculateType.useWith { myType = _ }
      else local
    }
  }

  case class TypeIdent(name: TypeName)(tpe: Type)(span: Span) extends TypeTree(span) {
    override protected def calculateType(using Context): Type =
      tpe

    override final def withSpan(span: Span): TypeIdent = TypeIdent(name)(tpe)(span)
  }

  object EmptyTypeIdent extends TypeIdent(nme.EmptyTypeName)(NoType)(NoSpan)

  case class TypeWrapper(tp: Type)(span: Span) extends TypeTree(span) {
    override protected def calculateType(using Context): Type = tp

    override final def withSpan(span: Span): TypeWrapper = TypeWrapper(tp)(span)
  }

  /** ref.type */
  case class SingletonTypeTree(ref: Tree)(span: Span) extends TypeTree(span) {
    override protected def calculateType(using Context): Type =
      ref.tpe

    override final def withSpan(span: Span): SingletonTypeTree = SingletonTypeTree(ref)(span)
  }

  case class RefinedTypeTree(underlying: TypeTree, refinements: List[Tree])(span: Span) extends TypeTree(span) {
    override protected def calculateType(using Context): Type =
      throw new TypeTreeToTypeError(this) // TODO

    override final def withSpan(span: Span): RefinedTypeTree = RefinedTypeTree(underlying, refinements)(span)
  }

  /** => T */
  case class ByNameTypeTree(result: TypeTree)(span: Span) extends TypeTree(span) {
    override protected def calculateType(using Context): Type =
      ExprType(result.toType)

    override final def withSpan(span: Span): ByNameTypeTree = ByNameTypeTree(result)(span)
  }

  /** tpt[args]
    * TypeBounds[Tree] for wildcard application: tpt[_], tpt[?]
    */
  case class AppliedTypeTree(tycon: TypeTree, args: List[TypeTree])(span: Span) extends TypeTree(span) {
    override protected def calculateType(using Context): Type =
      AppliedType(tycon.toType, args.map(_.toType))

    override final def withSpan(span: Span): AppliedTypeTree = AppliedTypeTree(tycon, args)(span)
  }

  /** qualifier#name */
  case class SelectTypeTree(qualifier: TypeTree, name: TypeName)(span: Span) extends TypeTree(span) {
    override protected def calculateType(using Context): Type =
      TypeRef(qualifier.toType, name)

    override final def withSpan(span: Span): SelectTypeTree = SelectTypeTree(qualifier, name)(span)
  }

  /** qualifier.name */
  case class TermRefTypeTree(qualifier: Tree, name: TermName)(span: Span) extends TypeTree(span) {
    override protected def calculateType(using Context): Type =
      TermRef(qualifier.tpe, name)

    override final def withSpan(span: Span): TermRefTypeTree = TermRefTypeTree(qualifier, name)(span)
  }

  /** arg @annot */
  case class AnnotatedTypeTree(tpt: TypeTree, annotation: Tree)(span: Span) extends TypeTree(span) {
    override protected def calculateType(using Context): Type =
      AnnotatedType(tpt.toType, annotation)

    override final def withSpan(span: Span): AnnotatedTypeTree = AnnotatedTypeTree(tpt, annotation)(span)
  }

  /** [bound] selector match { cases } */
  case class MatchTypeTree(bound: TypeTree, selector: TypeTree, cases: List[TypeCaseDef])(span: Span)
      extends TypeTree(span) {
    override protected def calculateType(using Context): Type =
      throw new TypeTreeToTypeError(this) // TODO

    override final def withSpan(span: Span): MatchTypeTree = MatchTypeTree(bound, selector, cases)(span)
  }

  case class TypeCaseDef(pattern: TypeTree, body: TypeTree)

  case class TypeTreeBind(name: TypeName, body: TypeTree, override val symbol: RegularSymbol)(span: Span)
      extends TypeTree(span)
      with DefTree(symbol) {
    override protected def calculateType(using Context): Type =
      TermRef(NoType, symbol)

    override final def withSpan(span: Span): TypeTreeBind = TypeTreeBind(name, body, symbol)(span)
  }

  case object EmptyTypeTree extends TypeTree(NoSpan) {
    override protected def calculateType(using Context): Type =
      NoType

    override final def withSpan(span: Span): TypeTree = EmptyTypeTree
  }

  case class TypeBoundsTree(low: TypeTree, high: TypeTree) {
    def toTypeBounds(using Context): TypeBounds =
      RealTypeBounds(low.toType, high.toType)
  }

  /** >: lo <: hi
    *  >: lo <: hi = alias  for RHS of bounded opaque type
    */
  case class BoundedTypeTree(bounds: TypeBoundsTree, alias: TypeTree)(span: Span) extends TypeTree(span) {
    override protected def calculateType(using Context): Type =
      BoundedType(bounds.toTypeBounds, alias.toType)

    override final def withSpan(span: Span): BoundedTypeTree = BoundedTypeTree(bounds, alias)(span)
  }

  case class NamedTypeBoundsTree(name: TypeName, bounds: TypeBounds)(span: Span) extends TypeTree(span) {
    override protected def calculateType(using Context): Type =
      NamedTypeBounds(name, bounds)

    override final def withSpan(span: Span): NamedTypeBoundsTree = NamedTypeBoundsTree(name, bounds)(span)
  }

  case class WildcardTypeBoundsTree(bounds: TypeBoundsTree)(span: Span) extends TypeTree(span) {
    override protected def calculateType(using Context): Type =
      WildcardTypeBounds(bounds.toTypeBounds)

    override final def withSpan(span: Span): WildcardTypeBoundsTree =
      WildcardTypeBoundsTree(bounds)(span)
  }

  case class TypeLambdaTree(tparams: List[TypeParam], body: TypeTree)(span: Span) extends TypeTree(span) {
    override protected def calculateType(using Context): Type =
      TypeLambda.fromParams(tparams)(_ => body.toType)

    override final def withSpan(span: Span): TypeLambdaTree = TypeLambdaTree(tparams, body)(span)
  }
}
