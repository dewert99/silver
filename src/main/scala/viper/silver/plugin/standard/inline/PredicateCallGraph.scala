package viper.silver.plugin.standard.inline

import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector
import org.jgrapht.graph.{DefaultDirectedGraph, DefaultEdge}
import org.jgrapht.traverse.TopologicalOrderIterator
import viper.silver.ast.{Node, PredicateAccess, PredicateAccessPredicate, Program}

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._

object PredicateCallGraph {
  type Graph[T] = DefaultDirectedGraph[T, DefaultEdge]
  type PredicateCallGraph = Graph[String]

  def graph(preds: Set[String], program: Program): PredicateCallGraph = {
    val graph = new PredicateCallGraph(classOf[DefaultEdge])
    preds.foreach(graph.addVertex)

    def process(src: String, node: Node): Unit =
      node.visit {
        case PredicateAccessPredicate(PredicateAccess(_, dst), _) =>
          if (preds(dst)) {
            graph.addEdge(src, dst)
          }
      }

    preds.foreach { pred =>
      program.findPredicate(pred).body.foreach(process(pred, _))
    }
    graph
  }

  def loopBreakers[T](graph: Graph[T]): (Set[T], Seq[T]) = {
    val recursive = Set() ++ graph.vertexSet().asScala.filter(x => graph.containsEdge(x,x))
    graph.removeAllVertices(recursive.asJava)
    @tailrec
    def iterate(oldLoopBreakers: Set[T], oldGraph: Graph[T]): (Set[T], Seq[T]) = {
      val stronglyConnected = new KosarajuStrongConnectivityInspector(graph)
      val sccs = stronglyConnected.stronglyConnectedSets().asScala.map(_.asScala)
      val newLoopBreakers = Set() ++ sccs.collect{
        case scc if scc.size > 1 => scc.maxBy(
          // Choose to avoid inlining the predicate called by the most other predicates within the scc
          v => oldGraph.incomingEdgesOf(v).asScala.count(e => scc.contains(oldGraph.getEdgeSource(e)))
        )
      }
      val loopBreakers = oldLoopBreakers ++ newLoopBreakers
      oldGraph.removeAllVertices(newLoopBreakers.asJava)
      if (loopBreakers == oldLoopBreakers) {
        val topo = new TopologicalOrderIterator(oldGraph).asScala.toSeq.reverse
        (loopBreakers, topo)
      } else {
        iterate(loopBreakers, oldGraph)
      }
    }
    iterate(recursive, graph)
  }
}
