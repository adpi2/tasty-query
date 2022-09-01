package tastyquery

import scala.annotation.tailrec

import dotty.tools.tasty.TastyBuffer.Addr
import dotty.tools.tasty.TastyFormat.NameTags
import tastyquery.ast.Names.*
import tastyquery.ast.Symbols.*
import tastyquery.ast.Types.{Type, Symbolic, Binders, PackageRef, TypeRef, SymResolutionProblem}

import scala.collection.mutable
import scala.collection.mutable.HashMap
import tastyquery.reader.classfiles.Classpaths
import tastyquery.reader.classfiles.Classpaths.{Classpath, Loader}
import scala.util.Try
import scala.util.control.NonFatal

import tastyquery.util.syntax.chaining.given

object Contexts {

  /** The current context */
  inline def fileCtx(using ctx: FileContext): FileContext = ctx
  transparent inline def ctx(using ctx: Context): Context = ctx
  transparent inline def clsCtx(using clsCtx: ClassContext): ClassContext = clsCtx
  transparent inline def defn(using ctx: Context): ctx.defn.type = ctx.defn

  def init(classpath: Classpath): Context =
    val ctx = classpath.loader { classloader =>
      val ctx = Context(Definitions(), classloader)
      ctx.classloader.initPackages()(using ctx)
      ctx
    }
    ctx.initializeFundamentalClasses()
    ctx

  /** Has the root been initialised already? Does not force, but returns true if at least one root was entered */
  private[tastyquery] def initialisedRoot(root: Loader.Root): Boolean =
    root.pkg.getDeclInternal(root.rootName).isDefined // module value
      || root.pkg.getDeclInternal(root.rootName.toTypeName).isDefined // class value

  /** Context is used throughout unpickling an entire project. */
  class Context private[Contexts] (val defn: Definitions, val classloader: Classpaths.Loader) {
    private given Context = this

    def withFile(root: Loader.Root, filename: String)(using Classpaths.permissions.LoadRoot): FileContext =
      new FileContext(defn, root, filename, classloader)

    def withRoot(root: Loader.Root)(using Classpaths.permissions.LoadRoot): ClassContext =
      new ClassContext(defn, classloader, defn.RootPackage, root)

    /** basically an internal method for loading Java classes embedded in Java descriptors */
    private[tastyquery] def getClassFromBinaryName(binaryName: String): Either[SymResolutionProblem, ClassSymbol] =
      (getRootIfDefined(binaryName): @unchecked) match
        case Right(root) =>
          root.pkg
            .getDecl(root.rootName.toTypeName)
            .collect { case cls: ClassSymbol => cls }
            .toRight(SymbolLookupException(root.fullName, s"perhaps it is not on the classpath"))
        case Left(err) => Left(err)

    /** Does there possibly exist a root for the given binary name. Does not force any classes covered by the name */
    private[tastyquery] def existsRoot(binaryName: String): Boolean =
      getRootIfDefined(binaryName).isRight

    /** Force a root to discover any top level symbols covered by the root. */
    private[tastyquery] def rootSymbolsIfDefined(binaryName: String): List[Symbol] =
      getRootIfDefined(binaryName) match
        case Right(root) =>
          root.pkg.getDecl(root.rootName.toTypeName).toList // class value
            ++ root.pkg.getDecl(root.rootName).toList // module value
            ++ root.pkg.getDecl(root.rootName.withObjectSuffix.toTypeName).toList // module class value
        case Left(_) => Nil

    /** Returns a root if there exists one on the classpath, does not force the underlying root symbols */
    private def getRootIfDefined(binaryName: String): Either[SymResolutionProblem, Loader.Root] =
      val (packageName, rootName) =
        val lastSep = binaryName.lastIndexOf('.')
        if lastSep == -1 then
          val rootName = termName(binaryName)
          (nme.EmptyPackageName, rootName)
        else
          import scala.language.unsafeNulls
          val packageName = binaryName.substring(0, lastSep)
          val rootName = termName(binaryName.substring(lastSep + 1))
          (classloader.toPackageName(packageName), rootName)
      def fullName = packageName.toTermName.select(rootName)
      try
        val pkg = PackageRef(packageName).resolveToSymbol
        pkg.possibleRoot(rootName).toRight(SymbolLookupException(fullName, s"no root exists in package $packageName"))
      catch
        case e: SymResolutionProblem =>
          Left(SymbolLookupException(fullName, s"unknown package $packageName"))

    def findSymbolFromRoot(path: List[Name]): Symbol =
      @tailrec
      def rec(symbol: Symbol, path: List[Name]): Symbol =
        path match
          case Nil =>
            symbol
          case name :: pathRest =>
            val owner = symbol match
              case owner: DeclaringSymbol => owner
              case _ =>
                throw IllegalArgumentException(
                  s"$symbol does not declare a scope, cannot find member ${name.toDebugString}"
                )
            val next = owner.getDecl(name).getOrElse {
              throw IllegalArgumentException(s"cannot find member ${name.toDebugString} in $symbol")
            }
            rec(next, pathRest)
      rec(defn.RootPackage, path)
    end findSymbolFromRoot

    def createClassSymbol(name: TypeName, owner: DeclaringSymbol): ClassSymbol =
      owner.getDeclInternal(name) match
        case None =>
          val cls = ClassSymbolFactory.createSymbol(name, owner)
          owner.addDecl(cls)
          cls
        case some =>
          throw ExistingDefinitionException(owner, name)

    def createSymbol(name: Name, owner: DeclaringSymbol): RegularSymbol =
      owner.getDeclInternal(name) match
        case None =>
          val sym = RegularSymbolFactory.createSymbol(name, owner)
          owner.addDecl(sym)
          sym
        case some =>
          throw ExistingDefinitionException(owner, name)

    def createPackageSymbolIfNew(name: TermName, owner: PackageClassSymbol): PackageClassSymbol = {
      def create(): PackageClassSymbol = {
        val trueOwner = if (owner == defn.EmptyPackage) defn.RootPackage else owner
        val sym = PackageClassSymbolFactory.createSymbol(name, trueOwner)
        sym
      }

      defn.RootPackage.findPackageSymbol(name) match {
        case Some(pkg) => pkg
        case None =>
          name match {
            case _: SimpleName => create()
            case QualifiedName(NameTags.QUALIFIED, prefix, _) =>
              if (prefix == owner.name) {
                create()
              } else {
                // create intermediate packages
                val newOwner = createPackageSymbolIfNew(prefix, owner)
                createPackageSymbolIfNew(name, newOwner)
              }
            case _ =>
              throw IllegalArgumentException(s"Unexpected package name: $name")
          }
      }
    }

    def getPackageSymbol(name: TermName): PackageClassSymbol = defn.RootPackage.findPackageSymbol(name).get

    private[Contexts] def initializeFundamentalClasses(): Unit = {
      val scalaPackage = createPackageSymbolIfNew(nme.scalaPackageName, defn.RootPackage)
      val javaLangPackage = createPackageSymbolIfNew(nme.javalangPackageName, defn.RootPackage)

      // TODO Assign superclasses and create members

      def initialise(cls: ClassSymbol): Unit =
        cls.withTypeParams(Nil, Nil)
        cls.initialised = true

      val anyClass = createClassSymbol(typeName("Any"), scalaPackage)
      initialise(anyClass)

      val nullClass = createClassSymbol(typeName("Null"), scalaPackage)
      initialise(nullClass)

      val nothingClass = createClassSymbol(typeName("Nothing"), scalaPackage)
      initialise(nothingClass)

      def fakeJavaLangClassIfNotFound(name: String): ClassSymbol =
        // TODO: add java.lang package in tests
        val tname = typeName(name)
        javaLangPackage.getDeclInternal(tname) match
          case Some(sym: ClassSymbol) =>
            sym
          case _ =>
            val sym = createClassSymbol(tname, javaLangPackage)
            initialise(sym)
            sym

      fakeJavaLangClassIfNotFound("Object")
      fakeJavaLangClassIfNotFound("Comparable")
      fakeJavaLangClassIfNotFound("Serializable")
      fakeJavaLangClassIfNotFound("String")
      fakeJavaLangClassIfNotFound("Throwable")
      fakeJavaLangClassIfNotFound("Error")
      fakeJavaLangClassIfNotFound("Exception")
    }
  }

  class ClassContext private[Contexts] (
    override val defn: Definitions,
    override val classloader: Classpaths.Loader,
    val owner: Symbol,
    val root: Loader.Root
  ) extends Context(defn, classloader) {

    inline given ClassContext = this

    /** The class root of this Context, will create and enter the symbol if it does not exist yet. */
    def classRoot: ClassSymbol =
      val name = root.rootName.toTypeName
      root.pkg
        .getDecl(name)
        .collect { case cls: ClassSymbol =>
          cls
        }
        .getOrElse(createClassSymbol(name, root.pkg).useWith(root.pkg.addDecl))

    /** The module class root of this Context, will create and enter the symbol if it does not exist yet. */
    def moduleClassRoot: ClassSymbol =
      val name = root.rootName.withObjectSuffix.toTypeName
      root.pkg
        .getDecl(name)
        .collect { case modcls: ClassSymbol =>
          modcls
        }
        .getOrElse(createClassSymbol(name, root.pkg).useWith(root.pkg.addDecl))

    /** The module value root of this Context, will create and enter the symbol if it does not exist yet. */
    def moduleRoot: RegularSymbol =
      val name = root.rootName
      root.pkg
        .getDecl(name)
        .collect { case mod: RegularSymbol =>
          mod
        }
        .getOrElse(createSymbol(name, root.pkg).useWith(root.pkg.addDecl))

    /*def createSymbol[T <: Symbol](name: Name, factory: SymbolFactory[T], addToDecls: Boolean): T =
      val sym = factory.createSymbol(name, owner)
      if (addToDecls) owner.addDecl(sym)
      sym*/

  }

  /** FileLocalInfo maintains file-local information, used during unpickling:
    * @param filename -- the .tasty file being unpickled, used for error reporting
    * @param localSymbols -- map of the symbols, created when unpickling the current file.
    *                     A symbol can be referred to from anywhere in the file, therefore once the symbol is added
    *                     to the file info, it is kept in the context and its subcontexts.
    *  @param enclosingBinders -- map of the type binders which have the current address in scope.
    *                          A type binder can only be referred to if it encloses the referring address.
    *                          A new FileLocalInfo (hence a new FileContext) is created when an enclosing is added
    *                          to mimic the scoping.
    */
  class FileLocalInfo(
    val filename: String,
    val localSymbols: mutable.HashMap[Addr, Symbol] = mutable.HashMap.empty,
    val enclosingBinders: Map[Addr, Binders] = Map.empty
  ) {
    def addEnclosingBinders(addr: Addr, b: Binders): FileLocalInfo =
      new FileLocalInfo(filename, localSymbols, enclosingBinders.updated(addr, b))
  }

  /** FileContext is used when unpickling a given .tasty file.
    * It extends the Context with the information,local to the file, and keeps track of the current owner.
    */
  class FileContext private[Contexts] (
    override val defn: Definitions,
    val classRoot: Loader.Root,
    val owner: Symbol,
    private val fileLocalInfo: FileLocalInfo,
    override val classloader: Classpaths.Loader
  ) extends Context(defn, classloader) { base =>

    private[Contexts] def this(
      defn: Definitions,
      classRoot: Loader.Root,
      filename: String,
      classloader: Classpaths.Loader
    ) = this(defn, classRoot, defn.RootPackage, new FileLocalInfo(filename), classloader)

    def withEnclosingBinders(addr: Addr, b: Binders): FileContext =
      new FileContext(defn, classRoot, owner, fileLocalInfo.addEnclosingBinders(addr, b), classloader)

    def withOwner(newOwner: Symbol): FileContext =
      if (newOwner == owner) this
      else new FileContext(defn, classRoot, newOwner, fileLocalInfo, classloader)

    def getFile: String = fileLocalInfo.filename

    def getEnclosingBinders(addr: Addr): Binders = fileLocalInfo.enclosingBinders(addr)

    def hasSymbolAt(addr: Addr): Boolean = fileLocalInfo.localSymbols.contains(addr)

    private def registerSym[T <: Symbol](addr: Addr, sym: T, addToDecls: Boolean): T =
      fileLocalInfo.localSymbols(addr) = sym
      if addToDecls then
        owner match
          case owner: DeclaringSymbol => owner.addDecl(sym)
          case _ => throw IllegalArgumentException(s"can not add $sym to decls of non-declaring symbol $owner")
      sym

    /** Creates a new symbol at @addr with @name. If `addToDecls` is true, the symbol is added to the owner's
      * declarations: this requires that the owner is a `DeclaringSymbol`, or else throws.
      *
      * @note `addToDecls` should be `true` for ValDef and DefDef, `false` for parameters and type parameters.
      * @note A method is added to the declarations of its class, but a nested method should not added
      *    to declarations of the outer method.
      */
    def createSymbol[T <: Symbol](addr: Addr, name: Name, factory: ClosedSymbolFactory[T], addToDecls: Boolean): T =

      def mkSymbol(name: Name, owner: Symbol): T =
        factory.createSymbol(name, owner)(using this)

      if !hasSymbolAt(addr) then registerSym(addr, mkSymbol(name, owner), addToDecls)
      else throw ExistingDefinitionException(owner, name)

    def createPackageSymbolIfNew(name: TermName): PackageClassSymbol = owner match {
      case owner: PackageClassSymbol => base.createPackageSymbolIfNew(name, owner)
      case owner                     => assert(false, s"Unexpected non-package owner: $owner")
    }

    def getSymbol(addr: Addr): Symbol =
      fileLocalInfo.localSymbols(addr)
    def getSymbol[T <: Symbol](addr: Addr, symbolFactory: SymbolFactory[T]): T =
      symbolFactory.castSymbol(fileLocalInfo.localSymbols(addr))
  }
}
