package tastyquery

import scala.collection.mutable

import tastyquery.Contexts.Context
import tastyquery.ast.Names.*
import tastyquery.ast.Symbols.*
import tastyquery.ast.Trees.*
import tastyquery.ast.Types.*

import Paths.*

class TypeSuite extends UnrestrictedUnpicklingSuite {
  def assertIsSymbolWithPath(path: DeclarationPath)(actualSymbol: Symbol)(using Context): Unit = {
    val expectedSymbol = resolve(path)
    assert(actualSymbol eq expectedSymbol, clues(actualSymbol, expectedSymbol))
  }

  extension (tpe: Type)
    def isAny(using Context): Boolean = tpe.isRef(resolve(name"scala" / tname"Any"))

    def isNothing(using Context): Boolean = tpe.isRef(resolve(name"scala" / tname"Nothing"))

    def isWildcard(using Context): Boolean =
      isBounded(_.isNothing, _.isAny)

    def isBounded(lo: Type => Boolean, hi: Type => Boolean)(using Context): Boolean = tpe match
      case WildcardTypeBounds(bounds) => lo(bounds.low) && hi(bounds.high)
      case _                          => false

    def isIntersectionOf(tpes: (Type => Boolean)*)(using Context): Boolean =
      def parts(tpe: Type, acc: mutable.ListBuffer[Type]): acc.type = tpe match
        case AndType(tp1, tp2) => parts(tp2, parts(tp1, acc))
        case tpe: Type         => acc += tpe
      val all = parts(tpe.widen, mutable.ListBuffer[Type]()).toList
      all.corresponds(tpes)((tpe, test) => test(tpe))

    def isApplied(cls: Type => Boolean, argRefs: Seq[Type => Boolean])(using Context): Boolean =
      tpe.widen match
        case AppliedType(tycon, args) if cls(tycon) =>
          args.corresponds(argRefs)((arg, argRef) => argRef(arg))
        case _ => false

    def isArrayOf(arg: Type => Boolean)(using Context): Boolean =
      isApplied(_.isRef(resolve(name"scala" / tname"Array")), Seq(arg))

  testWithContext("apply-recursive") {
    val RecApply = name"simple_trees" / tname"RecApply"

    val gcdSym = resolve(RecApply / name"gcd")
    val NumClass = resolve(RecApply / tname"Num")

    val Some(gcdTree @ _: DefDef) = gcdSym.tree: @unchecked

    var recCallCount = 0

    gcdTree.walkTree { tree =>
      tree match
        case recCall @ Apply(gcdRef @ Select(_, SignedName(SimpleName("gcd"), _, _)), _) =>
          recCallCount += 1

          assert(gcdRef.tpe.isRef(gcdSym), clue(gcdRef))

          assert(recCall.tpe.isRef(NumClass), clue(recCall))
        case _ => ()
    }

    assert(recCallCount == 1)

  }

  testWithContext("java.lang-is-not-loaded".fail) {
    resolve(name"java" / name"lang" / tname"String").declaredType
  }

  def applyOverloadedTest(name: String)(callMethod: String, paramCls: DeclarationPath)(using munit.Location): Unit =
    testWithContext(name) {
      val OverloadedApply = name"simple_trees" / tname"OverloadedApply"

      val callSym = resolve(OverloadedApply / termName(callMethod))
      val Acls = resolve(paramCls)
      val UnitClass = resolve(name"scala" / tname"Unit")

      val Some(callTree @ _: DefDef) = callSym.tree: @unchecked

      var callCount = 0

      callTree.walkTree { tree =>
        tree match
          case app @ Apply(fooRef @ Select(_, SignedName(SimpleName("foo"), _, _)), _) =>
            callCount += 1
            assert(app.tpe.isRef(UnitClass), clue(app)) // todo: resolve overloaded
            val fooSym = fooRef.tpe.termSymbol
            val List(List(aSym), _*) = fooSym.paramSymss: @unchecked
            assert(aSym.declaredType.isRef(Acls), clues(Acls.fullName, aSym.declaredType))
          case _ => ()
      }

      assert(callCount == 1)
    }

  applyOverloadedTest("apply-overloaded-int")("callA", name"scala" / tname"Int")
  applyOverloadedTest("apply-overloaded-gen")("callB", name"simple_trees" / tname"OverloadedApply" / tname"Box")
  applyOverloadedTest("apply-overloaded-nestedObj")(
    "callC",
    name"simple_trees" / tname"OverloadedApply" / objclass"Foo" / name"Bar"
  )
  // applyOverloadedTest("apply-overloaded-arrayObj")("callD", name"scala" / tname"Array") // TODO: re-enable when we add types to scala 2 symbols
  applyOverloadedTest("apply-overloaded-byName")("callE", name"simple_trees" / tname"OverloadedApply" / tname"Num")

  testWithContext("typeapply-recursive") {
    val RecApply = name"simple_trees" / tname"RecApply"

    val evalSym = resolve(RecApply / name"eval")
    val ExprClass = resolve(RecApply / tname"Expr")
    val NumClass = resolve(RecApply / tname"Num")
    val BoolClass = resolve(RecApply / tname"Bool")

    val evalParamss = evalSym.paramSymss

    val List(List(Tsym @ _), List(eSym)) = evalParamss: @unchecked

    val Some(evalTree @ _: DefDef) = evalSym.tree: @unchecked

    var recCallCount = 0

    evalTree.walkTree { tree =>
      tree match
        case recCall @ Apply(TypeApply(evalRef @ Select(_, SignedName(SimpleName("eval"), _, _)), List(targ)), _) =>
          recCallCount += 1

          assert(evalRef.tpe.isRef(evalSym), clue(evalRef))

          /* Because of GADT reasoning, the first two recursive call implicitly
           * have a [Num] type parameter, while the latter two have [Bool].
           */
          val expectedTargClass =
            if recCallCount <= 2 then NumClass
            else BoolClass

          assert(clue(targ).toType.isRef(expectedTargClass))
          assert(recCall.tpe.isRef(expectedTargClass), clue(recCall))
        case _ => ()
    }

    assert(recCallCount == 4) // 4 calls in the body of `eval`

  }

  testWithContext("basic-local-val") {
    val AssignPath = name"simple_trees" / tname"Assign"

    val fSym = resolve(AssignPath / name"f")
    val fTree = fSym.tree.get.asInstanceOf[DefDef]

    val List(Left(List(xParamDef))) = fTree.paramLists: @unchecked

    val IntClass = resolve(name"scala" / tname"Int")
    assert(xParamDef.symbol.declaredType.isRef(IntClass))

    fSym.declaredType match
      case mt: MethodType =>
        assert(mt.paramTypes.sizeIs == 1 && mt.paramTypes.head.isRef(IntClass))
        assert(mt.resultType.isRef(IntClass))
      case _ =>
        fail(s"$fSym does not have a MethodType", clues(fSym.declaredType))

    val x = SimpleName("x")
    val y = SimpleName("y")

    var xCount = 0
    var yCount = 0

    fTree.walkTree { tree =>
      tree match {
        case Ident(`x`) =>
          xCount += 1
          assert(tree.tpe.isOfClass(IntClass), clue(tree.tpe))
        case Ident(`y`) =>
          yCount += 1
          assert(tree.tpe.isOfClass(IntClass), clue(tree.tpe))
        case _ =>
          ()
      }
    }

    assert(xCount == 2, clue(xCount))
    assert(yCount == 1, clue(yCount))
  }

  testWithContext("term-ref") {
    val RepeatedPath = name"simple_trees" / tname"Repeated"

    val fSym = resolve(RepeatedPath / name"f")
    val fTree = fSym.tree.get.asInstanceOf[DefDef]

    var bitSetIdentCount = 0

    fTree.walkTree { tree =>
      tree match {
        case Ident(SimpleName("BitSet")) =>
          bitSetIdentCount += 1
          val sym = tree.tpe.asInstanceOf[Symbolic].resolveToSymbol
          assert(sym.name == name"BitSet", clue(sym.name.toDebugString))
          ()
        case _ =>
          ()
      }
    }

    assert(bitSetIdentCount == 1, clue(bitSetIdentCount))
  }

  testWithContext("free-ident") {
    val MatchPath = name"simple_trees" / tname"Match"

    val fSym = resolve(MatchPath / name"f")
    val fTree = fSym.tree.get.asInstanceOf[DefDef]

    val List(Left(List(xParamDef))) = fTree.paramLists: @unchecked

    val IntClass = resolve(name"scala" / tname"Int")
    assert(xParamDef.symbol.declaredType.isRef(IntClass))

    var freeIdentCount = 0

    fTree.walkTree { tree =>
      tree match {
        case tree: FreeIdent =>
          freeIdentCount += 1
          assert(tree.name == nme.Wildcard, clue(tree.name))
          assert(tree.tpe.isOfClass(IntClass), clue(tree.tpe))
        case _ =>
          ()
      }
    }

    assert(freeIdentCount == 2, clue(freeIdentCount))
  }

  testWithContext("return") {
    val ReturnPath = name"simple_trees" / tname"Return"

    val withReturnSym = resolve(ReturnPath / name"withReturn")
    val withReturnTree = withReturnSym.tree.get.asInstanceOf[DefDef]

    var returnCount = 0

    withReturnTree.walkTree { tree =>
      tree match {
        case Return(expr, from: Ident) =>
          returnCount += 1
          assert(from.tpe.isRef(withReturnSym), clue(from.tpe))
          assert(tree.tpe.isRef(resolve(name"scala" / tname"Nothing")))
        case _ =>
          ()
      }
    }

    assert(returnCount == 1, clue(returnCount))
  }

  testWithContext("assign") {
    val AssignPath = name"simple_trees" / tname"Assign"

    val fSym = resolve(AssignPath / name"f")
    val fTree = fSym.tree.get.asInstanceOf[DefDef]

    var assignCount = 0

    fTree.walkTree { tree =>
      tree match {
        case Assign(lhs, rhs) =>
          assignCount += 1
          val UnitClass = resolve(name"scala" / tname"Unit")
          assert(tree.tpe.isOfClass(UnitClass), clue(tree.tpe))
        case _ =>
          ()
      }
    }

    assert(assignCount == 1, clue(assignCount))
  }

  testWithContext("basic-scala2-types") {
    val ScalaRange = name"scala" / name"collection" / name"immutable" / tname"Range"

    val RangeClass = resolve(ScalaRange).asClass

    val BooleanClass = resolve(name"scala" / tname"Boolean")
    val IntClass = resolve(name"scala" / tname"Int")
    val Function1Class = resolve(name"scala" / tname"Function1")
    val IndexedSeqClass = resolve(name"scala" / name"collection" / name"immutable" / tname"IndexedSeq")

    // val start: Int
    val startSym = RangeClass.getDecl(name"start").get
    assert(startSym.declaredType.isOfClass(IntClass), clue(startSym.declaredType))
    assert(startSym.declaredType.isInstanceOf[ExprType], clue(startSym.declaredType)) // should this be TypeRef?

    // val isEmpty: Boolean
    val isEmptySym = RangeClass.getDecl(name"isEmpty").get
    assert(isEmptySym.declaredType.isOfClass(BooleanClass), clue(isEmptySym.declaredType))
    assert(isEmptySym.declaredType.isInstanceOf[ExprType], clue(isEmptySym.declaredType)) // should this be TypeRef?

    // def isInclusive: Boolean
    val isInclusiveSym = RangeClass.getDecl(name"isInclusive").get
    assert(isInclusiveSym.declaredType.isOfClass(BooleanClass), clue(isInclusiveSym.declaredType))
    assert(isInclusiveSym.declaredType.isInstanceOf[ExprType], clue(isInclusiveSym.declaredType))

    // def by(step: Int): Range
    locally {
      val bySym = RangeClass.getDecl(name"by").get
      val mt = bySym.declaredType.asInstanceOf[MethodType]
      assertEquals(List[TermName](name"step"), mt.paramNames, clue(mt.paramNames))
      assert(mt.paramTypes.sizeIs == 1)
      assert(mt.paramTypes.head.isOfClass(IntClass), clue(mt.paramTypes.head))
      assert(mt.resultType.isOfClass(RangeClass), clue(mt.resultType))
    }

    // def map[B](f: Int => B): IndexedSeq[B]
    locally {
      val mapSym = RangeClass.getDecl(name"map").get
      val pt = mapSym.declaredType.asInstanceOf[PolyType]
      val mt = pt.resultType.asInstanceOf[MethodType]
      assertEquals(List[TypeName](name"B".toTypeName), pt.paramNames, clue(pt.paramNames))
      assert(pt.paramTypeBounds.sizeIs == 1)
      assertEquals(List[TermName](name"f"), mt.paramNames, clue(mt.paramNames))
      assert(mt.paramTypes.sizeIs == 1)
      assert(mt.paramTypes.head.isOfClass(Function1Class), clue(mt.paramTypes.head))
      assert(mt.resultType.isOfClass(IndexedSeqClass), clue(mt.resultType))
    }
  }

  testWithContext("basic-java-class-dependency") {
    val BoxedJava = name"javacompat" / tname"BoxedJava"
    val JavaDefined = name"javadefined" / tname"JavaDefined"

    val boxedSym = resolve(BoxedJava / name"boxed")

    val (JavaDefinedRef @ _: Symbolic) = boxedSym.declaredType: @unchecked

    assertIsSymbolWithPath(JavaDefined)(JavaDefinedRef.resolveToSymbol)
  }

  testWithContext("bag-of-java-definitions") {
    val BagOfJavaDefinitions = name"javadefined" / tname"BagOfJavaDefinitions"

    val IntClass = resolve(name"scala" / tname"Int")
    val UnitClass = resolve(name"scala" / tname"Unit")

    def testDef(path: DeclarationPath)(op: Symbol => Unit): Unit =
      op(resolve(path))

    testDef(BagOfJavaDefinitions / name"x") { x =>
      assert(x.declaredType.isRef(IntClass))
    }

    testDef(BagOfJavaDefinitions / name"recField") { recField =>
      assert(recField.declaredType.isRef(resolve(BagOfJavaDefinitions)))
    }

    testDef(BagOfJavaDefinitions / name"printX") { printX =>
      val tpe = printX.declaredType.asInstanceOf[MethodType]
      assert(tpe.resultType.isRef(UnitClass))
    }

    testDef(BagOfJavaDefinitions / name"<init>") { ctor =>
      val tpe = ctor.declaredType.asInstanceOf[MethodType]
      assert(tpe.paramTypes.head.isRef(IntClass))
      assert(tpe.resultType.isRef(UnitClass))
    }

    testDef(BagOfJavaDefinitions / name"wrapXArray") { wrapXArray =>
      val tpe = wrapXArray.declaredType.asInstanceOf[MethodType]
      assert(tpe.resultType.isArrayOf(_.isRef(IntClass)))
    }

    testDef(BagOfJavaDefinitions / name"arrIdentity") { arrIdentity =>
      val tpe = arrIdentity.declaredType.asInstanceOf[MethodType]
      val JavaDefinedClass = resolve(name"javadefined" / tname"JavaDefined")
      assert(tpe.paramInfos.head.isArrayOf(_.isRef(JavaDefinedClass)))
      assert(tpe.resultType.isArrayOf(_.isRef(JavaDefinedClass)))
    }

  }

  testWithContext("bag-of-generic-java-definitions[signatures]") {
    val BagOfGenJavaDefinitions = name"javadefined" / tname"BagOfGenJavaDefinitions"
    val JavaDefinedClass = resolve(name"javadefined" / tname"JavaDefined")
    val GenericJavaClass = resolve(name"javadefined" / tname"GenericJavaClass")
    val JavaInterface1 = resolve(name"javadefined" / tname"JavaInterface1")
    val JavaInterface2 = resolve(name"javadefined" / tname"JavaInterface2")
    val ExceptionClass = resolve(name"java" / name"lang" / tname"Exception")

    def testDef(path: DeclarationPath)(op: Symbol => Unit): Unit =
      op(resolve(path))

    extension (tpe: Type)
      def isGenJavaClassOf(arg: Type => Boolean)(using Context): Boolean =
        tpe.isApplied(_.isRef(GenericJavaClass), List(arg))

    testDef(BagOfGenJavaDefinitions / name"x") { x =>
      assert(x.declaredType.isGenJavaClassOf(_.isRef(JavaDefinedClass)))
    }

    testDef(BagOfGenJavaDefinitions / name"getX") { getX =>
      val tpe = getX.declaredType.asInstanceOf[MethodType]
      assert(tpe.resultType.isGenJavaClassOf(_.isRef(JavaDefinedClass)))
    }

    testDef(BagOfGenJavaDefinitions / name"getXArray") { getXArray =>
      val tpe = getXArray.declaredType.asInstanceOf[MethodType]
      assert(tpe.resultType.isArrayOf(_.isGenJavaClassOf(_.isRef(JavaDefinedClass))))
    }

    testDef(BagOfGenJavaDefinitions / name"printX") { printX =>
      val tpe = printX.declaredType.asInstanceOf[PolyType]
      assert(tpe.paramTypeBounds.head.high.isRef(ExceptionClass))
    }

    testDef(BagOfGenJavaDefinitions / name"recTypeParams") { recTypeParams =>
      val tpe = recTypeParams.declaredType.asInstanceOf[TypeLambdaType]
      val List(tparamRefA, tparamRefY) = tpe.paramRefs: @unchecked
      assert(tparamRefA.bounds.high.isGenJavaClassOf(_ == tparamRefY))
    }

    testDef(BagOfGenJavaDefinitions / name"refInterface") { refInterface =>
      val tpe = refInterface.declaredType.asInstanceOf[TypeLambdaType]
      val List(tparamRefA) = tpe.paramRefs: @unchecked
      assert(
        tparamRefA.bounds.high.isIntersectionOf(_.isAny, _.isRef(JavaInterface1), _.isRef(JavaInterface2)),
        clues(tparamRefA.bounds)
      )
    }

    testDef(BagOfGenJavaDefinitions / name"genraw") { genraw =>
      assert(genraw.declaredType.isGenJavaClassOf(_.isWildcard))
    }

    testDef(BagOfGenJavaDefinitions / name"mixgenraw") { mixgenraw =>
      assert(mixgenraw.declaredType.isGenJavaClassOf(_.isGenJavaClassOf(_.isWildcard)))
    }

    testDef(BagOfGenJavaDefinitions / name"genwild") { genwild =>
      assert(genwild.declaredType.isGenJavaClassOf(_.isWildcard))
    }

    testDef(BagOfGenJavaDefinitions / name"gencovarient") { gencovarient =>
      assert(gencovarient.declaredType.isGenJavaClassOf(_.isBounded(_.isNothing, _.isRef(JavaDefinedClass))))
    }

    testDef(BagOfGenJavaDefinitions / name"gencontravarient") { gencontravarient =>
      assert(gencontravarient.declaredType.isGenJavaClassOf(_.isBounded(_.isRef(JavaDefinedClass), _.isAny)))
    }
  }

  testWithContext("java-class-parents") {
    val SubJavaDefined = name"javadefined" / tname"SubJavaDefined"

    val (SubJavaDefinedTpe @ _: ClassType) = resolve(SubJavaDefined).declaredType: @unchecked

    val JavaDefinedClass = resolve(name"javadefined" / tname"JavaDefined")
    val JavaInterface1Class = resolve(name"javadefined" / tname"JavaInterface1")
    val JavaInterface2Class = resolve(name"javadefined" / tname"JavaInterface2")

    assert(
      SubJavaDefinedTpe.rawParents
        .isIntersectionOf(_.isRef(JavaDefinedClass), _.isRef(JavaInterface1Class), _.isRef(JavaInterface2Class))
    )
  }

  testWithContext("java-class-signatures-[RecClass]") {
    val RecClass = name"javadefined" / tname"RecClass"

    val (RecClassTpe @ _: ClassType) = resolve(RecClass).declaredType: @unchecked

    val ObjectClass = resolve(name"java" / name"lang" / tname"Object")

    assert(RecClassTpe.rawParents.isRef(ObjectClass))
  }

  testWithContext("java-class-signatures-[SubRecClass]") {
    val SubRecClass = name"javadefined" / tname"SubRecClass"

    val (SubRecClassTpe @ _: ClassType) = resolve(SubRecClass).declaredType: @unchecked

    val RecClass = resolve(name"javadefined" / tname"RecClass")
    val JavaInterface1 = resolve(name"javadefined" / tname"JavaInterface1")

    val List(tparamT) = SubRecClassTpe.cls.typeParamSyms: @unchecked

    assert(
      SubRecClassTpe.rawParents.isIntersectionOf(
        _.isApplied(_.isRef(RecClass), List(_.isApplied(_.isRef(SubRecClassTpe.cls), List(_.isRef(tparamT))))),
        _.isRef(JavaInterface1)
      )
    )
  }

  testWithContext("select-method-from-java-class") {
    val BoxedJava = name"javacompat" / tname"BoxedJava"
    val getX = name"javadefined" / tname"JavaDefined" / name"getX"

    val xMethodSym = resolve(BoxedJava / name"xMethod")

    val Some(DefDef(_, _, _, Apply(getXSelection, _), _)) = xMethodSym.tree: @unchecked

    val (getXRef @ _: Symbolic) = getXSelection.tpe: @unchecked

    assertIsSymbolWithPath(getX)(getXRef.resolveToSymbol)
  }

  testWithContext("select-field-from-java-class") {
    val BoxedJava = name"javacompat" / tname"BoxedJava"
    val x = name"javadefined" / tname"JavaDefined" / name"x"

    val xFieldSym = resolve(BoxedJava / name"xField")

    val Some(DefDef(_, _, _, xSelection, _)) = xFieldSym.tree: @unchecked

    val (xRef @ _: Symbolic) = xSelection.tpe: @unchecked

    assertIsSymbolWithPath(x)(xRef.resolveToSymbol)
  }

  testWithContext("basic-scala-2-stdlib-class-dependency") {
    val BoxedCons = name"scala2compat" / tname"BoxedCons"
    val :: = name"scala" / name"collection" / name"immutable" / tname"::"

    val ConsClass = resolve(::)
    val JavaDefinedClass = resolve(name"javadefined" / tname"JavaDefined")

    val boxedSym = resolve(BoxedCons / name"boxed")

    val AppliedType(tycon, List(targ: Type)) = boxedSym.declaredType: @unchecked
    assert(tycon.isOfClass(ConsClass), clue(tycon))
    assert(targ.isOfClass(JavaDefinedClass), clue(targ))
  }

  testWithContext("select-method-from-scala-2-stdlib-class") {
    val BoxedCons = name"scala2compat" / tname"BoxedCons"
    val canEqual = name"scala" / name"collection" / tname"Seq" / name"canEqual"

    val AnyClass = resolve(name"scala" / tname"Any")
    val BooleanClass = resolve(name"scala" / tname"Boolean")

    val fooSym = resolve(BoxedCons / name"foo")

    val Some(DefDef(_, _, _, Apply(canEqualSelection, _), _)) = fooSym.tree: @unchecked

    val underlyingType = canEqualSelection.tpe match
      case termRef: TermRef => termRef.underlying
      case tpe              => fail("expected TermRef", clues(tpe))

    val mt = underlyingType.asInstanceOf[MethodType]
    assertEquals(List[TermName](name"that"), mt.paramNames, clue(mt.paramNames))
    assert(mt.paramTypes.sizeIs == 1, clue(mt.paramTypes))
    assert(mt.paramTypes.head.isOfClass(AnyClass), clue(mt.paramTypes.head))
    assert(mt.resultType.isOfClass(BooleanClass), clue(mt.resultType))
  }

  testWithContext("select-field-from-tasty-in-other-package:dependency-from-class-file") {
    val BoxedConstants = name"crosspackagetasty" / tname"BoxedConstants"
    val unitVal = name"simple_trees" / tname"Constants" / name"unitVal"

    val boxedUnitValSym = resolve(BoxedConstants / name"boxedUnitVal")

    val Some(DefDef(_, _, _, unitValSelection, _)) = boxedUnitValSym.tree: @unchecked

    val (unitValRef @ _: Symbolic) = unitValSelection.tpe: @unchecked

    assertIsSymbolWithPath(unitVal)(unitValRef.resolveToSymbol)
  }

  testWithContext("select-method-from-java-class-same-package-as-tasty") {
    // This tests reading top level classes in the same package, defined by
    // both Java and Tasty. If we strictly require that all symbols are defined
    // exactly once, then we must be careful to not redefine `ScalaBox`/`JavaBox`
    // when scanning a package from the classpath.

    val ScalaBox = name"mixjavascala" / tname"ScalaBox"
    val getX = name"mixjavascala" / tname"JavaBox" / name"getX"

    val xMethodSym = resolve(ScalaBox / name"xMethod")

    val Some(DefDef(_, _, _, Apply(getXSelection, _), _)) = xMethodSym.tree: @unchecked

    val (getXRef @ _: Symbolic) = getXSelection.tpe: @unchecked

    assertIsSymbolWithPath(getX)(getXRef.resolveToSymbol)
  }

  testWithContext("select-field-from-generic-class") {
    val GenClass = resolve(name"simple_trees" / tname"GenericClass").asClass
    val PolySelect = resolve(name"simple_trees" / tname"PolySelect").asClass
    val IntClass = resolve(name"scala" / tname"Int")

    val Some(DefDef(_, _, _, body, _)) = PolySelect.getDecl(name"testField").get.tree: @unchecked

    val Select(qual, fieldName) = body: @unchecked

    assert(clue(qual.tpe).isApplied(_.isOfClass(GenClass), List(_.isOfClass(IntClass))))
    assertEquals(fieldName, name"field")
    assert(clue(body.tpe).isOfClass(IntClass))
  }

  testWithContext("select-getter-from-generic-class") {
    val GenClass = resolve(name"simple_trees" / tname"GenericClass").asClass
    val PolySelect = resolve(name"simple_trees" / tname"PolySelect").asClass
    val IntClass = resolve(name"scala" / tname"Int")

    val Some(DefDef(_, _, _, body, _)) = PolySelect.getDecl(name"testGetter").get.tree: @unchecked

    val Select(qual, getterName) = body: @unchecked

    assert(clue(qual.tpe).isApplied(_.isOfClass(GenClass), List(_.isOfClass(IntClass))))
    assertEquals(getterName, name"getter")
    assert(clue(body.tpe).isOfClass(IntClass))
  }

  testWithContext("select-and-apply-method-from-generic-class") {
    val GenClass = resolve(name"simple_trees" / tname"GenericClass").asClass
    val PolySelect = resolve(name"simple_trees" / tname"PolySelect").asClass
    val IntClass = resolve(name"scala" / tname"Int")

    val Some(DefDef(_, _, _, body, _)) = PolySelect.getDecl(name"testMethod").get.tree: @unchecked

    val Apply(fun @ Select(qual, methodName), List(arg)) = body: @unchecked

    assert(clue(qual.tpe).isApplied(_.isOfClass(GenClass), List(_.isOfClass(IntClass))))
    methodName match {
      case SignedName(_, _, simpleName) => assertEquals(simpleName, name"method")
    }
    fun.tpe.widen match {
      case mt @ MethodType(List(paramName)) =>
        assertEquals(paramName, name"x")
        assert(clue(mt.paramTypes.head).isOfClass(IntClass))
        assert(clue(mt.resType).isOfClass(IntClass))
    }
    assert(clue(body.tpe).isOfClass(IntClass))
  }

  testWithContext("select-and-apply-poly-method") {
    val GenMethod = resolve(name"simple_trees" / tname"GenericMethod").asClass
    val PolySelect = resolve(name"simple_trees" / tname"PolySelect").asClass
    val IntClass = resolve(name"scala" / tname"Int")

    val Some(DefDef(_, _, _, body, _)) = PolySelect.getDecl(name"testGenericMethod").get.tree: @unchecked

    val Apply(tapp @ TypeApply(fun @ Select(qual, methodName), List(targ)), List(arg)) = body: @unchecked

    assert(clue(qual.tpe).isOfClass(GenMethod))
    methodName match {
      case SignedName(_, _, simpleName) => assertEquals(simpleName, name"identity")
    }
    tapp.tpe.widen match {
      case mt @ MethodType(List(paramName)) =>
        assertEquals(paramName, name"x")
        assert(clue(mt.paramTypes.head).isOfClass(IntClass))
        assert(clue(mt.resType).isOfClass(IntClass))
    }
    assert(clue(body.tpe).isOfClass(IntClass))
  }

}
