package rescala.rmi

import java.rmi.server.UnicastRemoteObject

import rescala.graph.Pulse

/**
 * This is the remote equivalent to Reactive.Dependency
 */
trait RemoteSender[V] extends java.rmi.Remote{
  @throws[java.rmi.RemoteException]
  def registerRemoteDependant(dependant: RemoteReceiver[V]): V
  @throws[java.rmi.RemoteException]
  def unregisterRemoteDependant(dependant: RemoteReceiver[V]): Unit
}



class RemoteSenderImpl[P](val pulsing: rescala.Signal[P]) extends UnicastRemoteObject  with RemoteSender[P] {

  var dependants: Set[RemoteReceiver[P]] = Set()

  println(s"adding remote observer on $pulsing")
  pulsing.observe(v => dependants.foreach(_.update(Pulse.Change(v))), e => dependants.foreach(_.update(Pulse.Exceptional(e))))(rescala.explicitEngine)

  override def registerRemoteDependant(dependant: RemoteReceiver[P]): P = {
    dependants += dependant
    pulsing.now(rescala.explicitEngine)
  }
  override def unregisterRemoteDependant( dependant: RemoteReceiver[P]): Unit = {
    dependants -= dependant
  }
}
