package rescala.fullmv

import java.util

class MutableTransactionSpanningTreeNode[T](val txn: T) extends util.ArrayList[MutableTransactionSpanningTreeNode[T]]
