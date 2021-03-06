package rescala.core

import scala.language.implicitConversions

/**
  * Provides names for dynamic dependencies based on their definition position to allow easier debugging
  */
case class REName(name: String) {
  def derive(derivation: String): String = s"$derivation($name)"
}

abstract class RENamed(rename: REName) {
  override def toString: String = rename.name
}

//  implicit def fromCreation[S <: Struct](implicit ct: CreationTicket[S]): REName = ct.rename
object REName extends LowPriorityREName {
  implicit def fromString(s: String): REName = REName(s)
  def named[T](name: String)(f: /* implicit */ REName => T) = f(REName(name))
}

trait LowPriorityREName {
  implicit def create(implicit file: sourcecode.Enclosing, line: sourcecode.Line): REName = REName(s"${file.value}:${line.value}")
}

