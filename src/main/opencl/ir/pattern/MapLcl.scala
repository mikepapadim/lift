package opencl.ir.pattern

import ir.ast._
import lift.arithmetic.PosVar

/**
 * Applicable rules:
 *  - MapLcl(f) => toGlobal(MapLcl(f))
 *  - MapLcl(f) => toLocal(MapLcl(f))
 */
case class MapLcl(dim: Int, override val f: Lambda1)
extends AbstractMap(f, "MapLcl", PosVar("l_id")) {
  override def copy(f: Lambda): Pattern = MapLcl(dim, f)
  var shouldUnroll = false
  var emitBarrier = true
}

object MapLcl {
  def apply(f: Lambda1) = new MapLcl(0, f) // o is default

  def apply(dim: Int) = (f: Lambda1) => new MapLcl(dim, f)
}
