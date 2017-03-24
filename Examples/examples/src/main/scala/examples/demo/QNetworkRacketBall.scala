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

@multitier
object QNetworkRacketBall {
//  trait Server extends ServerPeer[Client]
//  trait Client extends ClientPeer[Server]
//
//  val shapes = placed[Server] { implicit! => Var[List[Shape]](List.empty) }
//  val panel = placed[Server].local { implicit! => new ShapesPanel(shapes) }
//  val panelSize = placed[Server] { implicit! => panel.sigSize }
//
//  val playingField = placed[Server].local { implicit! => new PlayingField(panel.width.map(_ - 25), panel.height.map(_ - 25)) }
//  val racket = placed[Server].local { implicit! => new Racket(playingField.width, true, playingField.height, panel.Mouse.y) }
//  placed[Server].local { implicit ! => shapes.transform(playingField.shape :: racket.shape :: _) }
//
//  val opponentInput = placed[Client] { implicit! =>
//    val opponent = new Opponent(panelSize.asLocal, shapes.asLocal)
//    opponent.main(Array())
//    opponent.panel2.Mouse.y
//  }
//  val racket2 = placed[Server].local { implicit! =>
//    new Racket(playingField.width, false, playingField.height, opponentInput.asLocal)
//  }
//  placed[Server].local { implicit! => shapes.transform(racket2.shape :: _) }
//
//  placed[Server].local { implicit! =>
//    def makeBall(initVx: Double, initVy: Double) = {
//      val bouncingBall = new BouncingBall(initVx, initVy, Var(50), panel.Mouse.middleButton.pressed)
//      shapes.transform(bouncingBall.shape :: _)
//
//      val fieldCollisions = playingField.colliders(bouncingBall.shape)
//      bouncingBall.horizontalBounceSources.transform(fieldCollisions.left :: fieldCollisions.right :: _)
//      bouncingBall.verticalBounceSources.transform(fieldCollisions.top :: fieldCollisions.bottom :: _)
//
//      val racketCollision = racket.collisionWith(bouncingBall.shape) || racket2.collisionWith(bouncingBall.shape)
//      bouncingBall.horizontalBounceSources.transform(racketCollision :: _)
//    }
//    makeBall(200d, 150d)
//    makeBall(-200d, 100d)
//
//    main(Array())
//  }
}

//object GameServer extends App {
//  retier.multitier setup new QNetworkRacketBall.Server {
//    def connect = TCP(1099)
//  }
//}
//
//object GameClient extends App {
//  retier.multitier setup new QNetworkRacketBall.Client {
//    def connect = TCP("localhost", 1099)
//  }
//}
