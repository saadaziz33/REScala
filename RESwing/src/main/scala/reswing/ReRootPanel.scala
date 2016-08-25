package reswing

import scala.language.implicitConversions
import scala.swing.{Component, RootPanel}
import scala.swing.event.{ComponentAdded, ComponentRemoved}

trait ReRootPanel extends ReUIElement {
  protected def peer: RootPanel

  def contents: ReSwingValue[Component]

  contents using (
      { () => peer.contents.headOption getOrElse null },
      peer.contents_= _,
      classOf[ComponentAdded], classOf[ComponentRemoved])
}

object ReRootPanel {
  implicit def toRootPanel(component: ReRootPanel): RootPanel = component.peer
}
