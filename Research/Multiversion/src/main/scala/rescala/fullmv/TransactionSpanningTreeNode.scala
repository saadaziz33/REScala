package rescala.fullmv
import java.util

trait TransactionSpanningTreeNode[T] {
  val txn: T
  def iterator(): java.util.Iterator[MutableTransactionSpanningTreeNode[T]]
  def addChild(child: MutableTransactionSpanningTreeNode[T]): Unit
}
class MutableTransactionSpanningTreeNode[T](val txn: T) extends java.util.ArrayList[MutableTransactionSpanningTreeNode[T]] with TransactionSpanningTreeNode[T] {
  override def addChild(e: MutableTransactionSpanningTreeNode[T]): Unit = super.add(e)
}
class MutableTransactionSpanningTreeRoot[T](val txn: T) extends TransactionSpanningTreeNode[T] {
  @volatile var children: Array[MutableTransactionSpanningTreeNode[T]] = new Array(6)
  @volatile var size: Int = 0
  override def addChild(child: MutableTransactionSpanningTreeNode[T]): Unit = {
    if(children.length == size) {
      val newChildren = new Array[MutableTransactionSpanningTreeNode[T]](children.length + (children.length >> 1))
      System.arraycopy(children, 0, newChildren, 0, size)
      children = newChildren
    }
    children(size) = child
    size += 1
  }

  override def iterator(): util.Iterator[MutableTransactionSpanningTreeNode[T]] = new util.Iterator[MutableTransactionSpanningTreeNode[T]] {
    private var idx = 0
    override def next(): MutableTransactionSpanningTreeNode[T] = {
      val r = children(idx)
      idx += 1
      r
    }
    override def hasNext: Boolean = idx < size
  }
}
