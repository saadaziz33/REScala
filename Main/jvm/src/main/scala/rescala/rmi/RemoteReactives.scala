package rescala.rmi

import java.io.ObjectStreamException
import java.rmi.Naming

import rescala.graph.Pulse


object RemoteReactives {
  def rebind[A](name: String, signal: rescala.Signal[A]): Unit = Naming.rebind(name, new RemoteSenderImpl(signal))
  def lookupSignal[A](name: String): rescala.Signal[A] = from(Naming.lookup(name).asInstanceOf[RemoteSender[A]])

  def from[A](rs: RemoteSender[A]): rescala.Signal[A] = {
    lazy val inner: rescala.Var[A] = rescala.Var(rs.registerRemoteDependant(new RemoteReceiverImpl[A] {
      override def update(pulse: Pulse[A]): Unit = {
        println("received pulse")
        rescala.explicitEngine.plan(inner)(t => inner.admitPulse(pulse)(t))
      }
    }))
    inner
  }

  private lazy val _registry = java.rmi.registry.LocateRegistry.createRegistry(1099)
  def requireRegistry() = _registry
  def unbindAll() = _registry.list().foreach(_registry.unbind)


}

case class SerializableSignal[A](rs: RemoteSender[A]) {
  @throws(classOf[ObjectStreamException])
  def readResolve(): Any = RemoteReactives.from(rs)
}


object Test {
  def main(args: Array[String]): Unit = {
    import rescala._
    RemoteReactives.requireRegistry()
    val v = Var(5)
    RemoteReactives.rebind("v", v)
    val ov = RemoteReactives.lookupSignal[Int]("v")
    println(s"${v.now} == ${ov.now}")

    val ho = Var(v)
    RemoteReactives.rebind("ho", ho)
    val oho = RemoteReactives.lookupSignal[Signal[Int]]("ho")
    val flat = oho.flatten
    println(s"${ho.now.now} == ${oho.now.now} == ${flat.now}")

    v() = 100
    println(s"${v.now} == ${ov.now}")
    println(s"${ho.now.now} == ${oho.now.now} == ${flat.now}")


    RemoteReactives.unbindAll
  }
}
