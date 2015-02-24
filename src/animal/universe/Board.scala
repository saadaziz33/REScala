package animal.universe

import animal.types.Pos
import rescala._
import rescala.turns.Engines.default

import scala.collection.immutable.IndexedSeq
import scala.collection.mutable
import scala.collection.mutable.Map
import scala.util.Random


object Board {
  def square(range: Int): IndexedSeq[Pos] = for (x <- -range to range; y <- -range to range) yield Pos(x, y)
  def proximity(pos: Pos, range: Int): IndexedSeq[Pos] = square(range).map(pos + _).sortBy(pos.distance)
}

/**
 * Mutable data structure which stores board elements in 2-dimensional coordinates.
 * A Board is infinite, but width and height specify the area being displayed.
 */
class Board(val width: Int, val height: Int) {
  val elements: Map[Pos, BoardElement] = new mutable.HashMap
  val allPositions = (for (x <- 0 to width; y <- 0 to height) yield Pos(x, y)).toSet

  val elementSpawned = Evt[BoardElement]()
  //#EVT  == after(add)
  val elementRemoved = Evt[BoardElement]()
  //#EVT  == after(remove)
  val elementsChanged = elementSpawned || elementRemoved
  //#EVT
  val animalSpawned = elementSpawned && (_.isInstanceOf[Animal])
  //#EVT
  val animalRemoved = elementRemoved && (_.isInstanceOf[Animal])
  //#EVT
  val animalsBorn = animalSpawned.iterate(0)(_ + 1)
  //#SIG #IF
  val animalsDied = animalRemoved.iterate(0)(_ + 1)
  //#SIG #IF
  val animalsAlive: Signal[Int] = Signals.lift(animalsBorn, animalsDied){ _ - _ }

  /** adds a board element at given position */
  def add(be: BoardElement, pos: Pos): Unit = {
    elements.put(pos, be)
    elementSpawned(be)
  }

  /** removes the board element if present in the board */
  def remove(be: BoardElement): Unit = getPosition(be).foreach(remove)
  def remove(pos: Pos): Unit = {
    val e = elements.remove(pos)
    if (e.isDefined) elementRemoved(e.get)
  }

  /** @return the elements in this board nearby pos */
  def nearby(pos: Pos, range: Int) = Board.proximity(pos, range).map(elements.get).flatten

  /** @return the immediate neighbors of the given position */
  def neighbors(pos: Pos) = nearby(pos, 1)

  /** @return true if pos is free */
  def isFree(pos: Pos) = !elements.contains(pos)

  /** clears the current element from pos */
  private def clear(pos: Pos): Option[BoardElement] = elements.remove(pos)

  /** @return the nearest free position to pos */
  def nearestFree(pos: Pos) = Board.proximity(pos, 1).find(isFree)

  /** moves pos in direction dir if possible (when target is free) */
  def moveIfPossible(pos: Pos, dir: Pos): Unit = {
    val newPos = pos + dir
    if (isFree(newPos) && !isFree(pos)) {
      val e = clear(pos)
      elements.put(newPos, e.get)
    }
  }

  /** @return the position of the given BoardElement. slow. */
  def getPosition(be: BoardElement) = {
    elements.collectFirst {
      case (pos, b) if b == be => pos
    }
  }

  /** @return a random free position on this board */
  def randomFreePosition(random: Random) = {
    val possiblePositions = allPositions.diff(elements.keySet).toVector
    possiblePositions(random.nextInt(possiblePositions.length))
  }

  /** @return textual representation for drawing this board to console */
  def dump: String = {
    def repr(be: Option[BoardElement]) = be match {
      case None => '.'
      case Some(m: Male) if m.isAdult.now => 'm'
      case Some(f: Female) if f.isAdult.now => if (f.isPregnant.now) 'F' else 'f'
      case Some(x: Animal) => 'x'
      case Some(p: Plant) => '#'
      case Some(_) => '?'
    }
    val lines = for (y <- 0 to height)
    yield (0 to width).map(x => repr(elements.get(Pos(x, y)))).mkString
    lines.mkString("\n")
  }
}


abstract class BoardElement(implicit val world: World) {

  /** A signal denoting if this element is dead ( = should be removed from the board) */
  val isDead: Signal[Boolean]
  // Abstract (//#SIG)
  lazy val dies: Event[Unit] = isDead changedTo true //#EVT //#IF

  /** Some imperative code that is called each tick */
  def doStep(pos: Pos): Unit = {}
}