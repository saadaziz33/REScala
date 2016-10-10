package tests.rescala.meta

import org.scalatest.FunSuite
import rescala.api.Api
import rescala.engines.CommonEngines
import rescala.meta.optimization.MapFusion
import rescala.meta.{DataFlowGraph, EngineReifier}

class MapFusionTest extends FunSuite {
  val SynchronousReifier = new EngineReifier()(CommonEngines.synchron)

  test("Event map fusion test") {

    val g = new DataFlowGraph()
    val api = new Api.metaApi(g)

    var x = 0
    val e = api.Evt[Int]()
    val e2 = e.map((x: Int) => x * 2)
    val e3 = e2.map((x: Int) => x + 2)
    val o = e3 += { x = _ }
    e.fire(2)
    assert(g.numNodes == 4, "Graph should contain 4 nodes before fusion")

    MapFusion.optimize(g)
    assert(g.numNodes == 3, "Graph should contain 3 nodes after fusion")
    e.reify(SynchronousReifier)
    assert(x == 6, "Propagation of pre-optimization values should still work correctly")
    e.fire(3)
    e.reify(SynchronousReifier)
    assert(x == 8, "Propagation of post-optimization values should work correctly")
  }

  test("Signal map fusion test") {
    import rescala.engines.CommonEngines.synchron

    val g = new DataFlowGraph()
    val api = new Api.metaApi(g)

    val v = api.Var(1)
    val v2 = v.map((x: Int) => x * 2)
    val v3 = v2.map((x: Int) => x + 2)
    val e = v3.changed
    v.set(2)
    assert(g.numNodes == 4, "Graph should contain 4 nodes before fusion")

    MapFusion.optimize(g)
    assert(g.numNodes == 3, "Graph should contain 3 nodes after fusion")
    assert(v3.reify(SynchronousReifier).now == 6, "Propagation of pre-optimization values should still work correctly")
    v.set(3)
    assert(v3.reify(SynchronousReifier).now == 8, "Propagation of post-optimization values should work correctly")
  }

  test("Multi fusion test") {
    import rescala.engines.CommonEngines.synchron

    val g = new DataFlowGraph()
    val api = new Api.metaApi(g)

    val v = api.Var(1)
    val v2 = v.map((x: Int) => x * 2)
    val v3 = v2.map((x: Int) => x + 2)
    val v4 = v3.map((x: Int) => x * 3)
    val e = v4.changed
    v.set(2)
    assert(g.numNodes == 5, "Graph should contain 5 nodes before fusion")

    MapFusion.optimize(g)
    assert(g.numNodes == 3, "Graph should contain 3 nodes after fusion")
    assert(v4.reify(SynchronousReifier).now == 18, "Propagation of pre-optimization values should still work correctly")
    v.set(3)
    assert(v4.reify(SynchronousReifier).now == 24, "Propagation of post-optimization values should work correctly")
  }

  test("No fusion test") {
    import rescala.engines.CommonEngines.synchron

    val g = new DataFlowGraph()
    val api = new Api.metaApi(g)

    val v = api.Var(1)
    val v2 = v.map((x: Int) => x * 2)
    val v3 = v2.map((x: Int) => x + 2)
    val v4 = v2.map((x: Int) => x * 3)
    val e = v3.changed
    v.set(2)
    assert(g.numNodes == 5, "Graph should contain 5 nodes before fusion")

    MapFusion.optimize(g)
    assert(g.numNodes == 5, "Graph should still contain 5 nodes after fusion")
    assert(v3.reify(SynchronousReifier).now == 6, "Propagation of pre-optimization values should still work correctly")
    assert(v4.reify(SynchronousReifier).now == 12, "Propagation of pre-optimization values should still work correctly")
    v.set(3)
    assert(v3.reify(SynchronousReifier).now == 8, "Propagation of post-optimization values should work correctly")
    assert(v4.reify(SynchronousReifier).now == 18, "Propagation of post-optimization values should work correctly")
  }
}
