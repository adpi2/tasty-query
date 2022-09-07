package tastyquery.testutil.jdk

import tastyquery.testutil.TestPlatform
import java.nio.file.Files
import java.nio.file.Paths
import tastyquery.reader.classfiles.Classpaths.Classpath
import scala.util.Properties
import tastyquery.jdk.ClasspathLoaders
import java.io.File

object JavaTestPlatform extends TestPlatform {
  private val ResourceProperty = "test-resources"
  private val StdLibProperty = "std-library"

  private def resourcesDir = Properties.propOrNone(ResourceProperty).get
  private def stdLibPaths = {
    import scala.language.unsafeNulls
    Properties.propOrEmpty(StdLibProperty).split(File.pathSeparator).toList
  }

  def loadClasspath(): Classpath = {
    val kinds = Set(ClasspathLoaders.FileKind.Tasty, ClasspathLoaders.FileKind.Class)
    val javaHome = Paths.get(Properties.javaHome).toOption
    val rtJar = javaHome.flatMap(_.resolve("lib/rt.jar").toOption).filter(Files.exists(_)).map(_.toString)
    val parts = resourcesDir :: rtJar.toList ::: stdLibPaths
    ClasspathLoaders.read(parts, kinds)
  }

  extension [T] (nullable: T | Null)
    def toOption: Option[T] = nullable match
      case null => None
      case value => Some(value.asInstanceOf[T])
}
