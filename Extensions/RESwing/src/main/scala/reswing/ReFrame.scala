package reswing

import scala.language.implicitConversions
import scala.swing.{Component, Dimension, Frame, Point, Rectangle}

class ReFrame(
    contents: ReSwingValue[Component] = (),
    val title: ReSwingValue[String] = (),
    size: ReSwingValue[Dimension] = (),
    location: ReSwingValue[Point] = (),
    bounds: ReSwingValue[Rectangle] = (),
    minimumSize: ReSwingValue[Dimension] = (),
    maximumSize: ReSwingValue[Dimension] = (),
    preferredSize: ReSwingValue[Dimension] = ())
  extends
    ReWindow(contents, size, location, bounds,
             minimumSize, maximumSize, preferredSize)
  with
    ReRichWindow {
  override protected lazy val peer = new Frame
}

object ReFrame {
  implicit def toFrame(component: ReFrame): Frame = component.peer
}
