package examples.demo

import examples.demo.LFullyModularBall.BouncingBall
import examples.demo.MPlayingFieldBall.PlayingField
import examples.demo.ORacketMultiBall.Racket
import examples.demo.PSplitscreenRacketBall.Opponent
import examples.demo.ui._
import rescala._
import retier._
import retier.architectures.ClientServer._
import retier.rescalaTransmitter._
import retier.serializable.upickle._
import retier.tcp._

object Transmittables {
  import java.awt.{Color, Dimension}
  import retier.transmission._

  implicit val dimensionTransmittable = new PullBasedTransmittable[Dimension, (Int, Int), Dimension] {
    def send(value: Dimension, remote: RemoteRef) = (value.width, value.height)
    def receive(value: (Int, Int), remote: RemoteRef) = new Dimension(value._1, value._2)
  }

  implicit val colorTransmittable = new PullBasedTransmittable[Color, (Int, Int, Int, Int), Color] {
    def send(value: Color, remote: RemoteRef) = (value.getRed, value.getGreen, value.getBlue, value.getAlpha)
    def receive(value: (Int, Int, Int, Int), remote: RemoteRef) = new Color(value._1, value._2, value._3, value._4)
  }
  type Repr = (Signal[Int], Signal[Int], Signal[Int], Option[Signal[Int]], Signal[Option[Color]], Signal[Option[Color]])
  implicit def shapeTransmittable[Inter](implicit transmittable: Transmittable[Repr, Inter, Repr]) = new PullBasedTransmittable[Shape, Inter, Shape] {
    def send(value: Shape, remote: RemoteRef) = transmittable send (value match {
      case shape: Circle =>
        (shape.centerX, shape.centerY, shape.diameter, None, shape.border, shape.fill)
      case shape: Rectangle =>
        (shape.centerX, shape.centerY, shape.hitboxWidth, Some(shape.hitboxHeight), shape.border, shape.fill)
    })
    def receive(value: Inter, remote: RemoteRef) =
      (transmittable receive value) match {
        case (centerX, centerY, diameter, None, border, fill) =>
          new Circle(centerX, centerY, diameter, border, fill)
        case (centerX, centerY, hitboxWidth, Some(hitboxHeight), border, fill) =>
          new Rectangle(centerX, centerY, hitboxWidth, hitboxHeight, border, fill)
      }
  }
}
import Transmittables._

@multitier
object QNetworkRacketBall {
  trait Server extends ServerPeer[Client]
  trait Client extends ClientPeer[Server]

  val shapes = placed[Server] { implicit! => Var[List[Shape]](List.empty) }
  val panel = placed[Server].local { implicit! => new ShapesPanel(shapes) }
  val panelSize = placed[Server] { implicit! => panel.sigSize }

  val playingField = placed[Server].local { implicit! => new PlayingField(panel.width.map(_ - 25), panel.height.map(_ - 25)) }
  val racket = placed[Server].local { implicit! => new Racket(playingField.width, true, playingField.height, panel.Mouse.y) }
  placed[Server].local { implicit ! => shapes.transform(playingField.shape :: racket.shape :: _) }

  val opponentInput = placed[Client] { implicit! =>
    val opponent = new Opponent(panelSize.asLocal, shapes.asLocal)
    opponent.main(Array())
    opponent.panel2.Mouse.y
  }
  val racket2 = placed[Server].local { implicit! =>
    new Racket(playingField.width, false, playingField.height, opponentInput.asLocal /* Signal {
      remote[Client].connected() match {
        case opponent :: _ => (opponentInput from opponent).asLocal()
        case _ => 0
      }
    }*/)
  }
  placed[Server].local { implicit! => shapes.transform(racket2.shape :: _) }

  placed[Server].local { implicit! =>
    def makeBall(initVx: Double, initVy: Double) = {
      val bouncingBall = new BouncingBall(initVx, initVy, Var(50), panel.Mouse.middleButton.pressed)
      shapes.transform(bouncingBall.shape :: _)

      val fieldCollisions = playingField.colliders(bouncingBall.shape)
      bouncingBall.horizontalBounceSources.transform(fieldCollisions.left :: fieldCollisions.right :: _)
      bouncingBall.verticalBounceSources.transform(fieldCollisions.top :: fieldCollisions.bottom :: _)

      val racketCollision = racket.collisionWith(bouncingBall.shape) || racket2.collisionWith(bouncingBall.shape)
      bouncingBall.horizontalBounceSources.transform(racketCollision :: _)
    }
    makeBall(200d, 150d)
    makeBall(-200d, 100d)

    new Main {
      override val panel: ShapesPanel = panel
    }.main(Array())
  }
}

object GameServer extends App {
  retier.multitier setup new QNetworkRacketBall.Server {
    def connect = TCP(1099)
  }
}

object GameClient extends App {
  retier.multitier setup new QNetworkRacketBall.Client {
    def connect = TCP("localhost", 1099)
  }
}
