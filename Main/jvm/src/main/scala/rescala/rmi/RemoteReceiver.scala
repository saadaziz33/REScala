package rescala.rmi

import java.rmi.server.UnicastRemoteObject

import rescala.graph.Pulse

trait RemoteReceiver[V] extends java.rmi.Remote {
  @throws[java.rmi.RemoteException]
  def update(pulse: Pulse[V]): Unit
}


abstract class RemoteReceiverImpl[P] extends UnicastRemoteObject with RemoteReceiver[P]
