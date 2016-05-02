package analysis

import analysis.AccessCounts.SubstitutionMap
import apart.arithmetic.ArithExpr._
import apart.arithmetic._
import ir.ast.{Map => _, _}
import ir.view._
import ir.{TypeChecker, UnallocatedMemory, UndefType}
import opencl.generator.OpenCLGenerator.NDRange
import opencl.generator.RangesAndCounts
import opencl.ir.OpenCLMemoryAllocator
import opencl.ir.pattern.{MapGlb, MapLcl}


object AccessPatterns {

  def apply(lambda: Lambda,
    localSize: NDRange = Array(?,?,?),
    globalSize: NDRange = Array(?,?,?),
    valueMap: SubstitutionMap = collection.immutable.Map()
  ) = new AccessPatterns(lambda, localSize, globalSize, valueMap)

}

abstract class AccessPattern
object CoalescedPattern extends AccessPattern
object UnknownPattern extends AccessPattern

class AccessPatterns(
  val lambda: Lambda,
  val localSize: NDRange,
  val globalSize: NDRange,
  val valueMap: SubstitutionMap
) {

  private var readPatterns = Map[Expr, AccessPattern]()
  private var writePatterns = Map[Expr, AccessPattern]()

  private var coalescingId: Option[Var] = None

  if (lambda.body.t == UndefType)
    TypeChecker(lambda)

  if (lambda.body.mem == UnallocatedMemory) {
    RangesAndCounts(lambda, localSize, globalSize, valueMap)
    OpenCLMemoryAllocator(lambda)
  }

  if (lambda.body.view == NoView)
    View(lambda)

  determinePatterns(lambda.body)

  def getReadPatterns = readPatterns
  def getWritePatterns = writePatterns

  def apply() =
    (readPatterns, writePatterns)

  private def isCoalesced(view: View): Boolean = {
    val newVar = Var("")
    val accessLocation = ViewPrinter.emit(view)

    if (coalescingId.isEmpty)
      return false

    val i0 = substitute(accessLocation, Map(coalescingId.get -> (newVar + 0)))
    val i1 = substitute(accessLocation, Map(coalescingId.get -> (newVar + 1)))

    i1 - i0 == Cst(1)
  }

  private def getPattern(view: View) = {
    if (isCoalesced(view))
      CoalescedPattern
    else
      UnknownPattern
  }

  private def determinePatterns(expr: Expr): Unit = {

    expr match {
      case FunCall(f, args@_*) =>
        args.foreach(determinePatterns)

        f match {
          case mapLcl@MapLcl(0, nestedLambda) =>
            coalescingId = Some(mapLcl.loopVar)
            determinePatterns(nestedLambda.body)
            coalescingId = None

          case mapGlb@MapGlb(0, nestedLambda) =>
            coalescingId = Some(mapGlb.loopVar)
            determinePatterns(nestedLambda.body)
            coalescingId = None

          case fp: FPattern => determinePatterns(fp.f.body)
          case uf: UserFun =>

            args.foreach(arg =>
              readPatterns += arg -> getPattern(arg.view)
            )

            writePatterns += expr -> getPattern(expr.outputView)

          case _ =>
        }

      case _ =>
    }

  }

}