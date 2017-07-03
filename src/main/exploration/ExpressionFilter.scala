package exploration

import analysis.MemoryAmounts
import com.typesafe.scalalogging.Logger
import ir.ast._
import opencl.generator.NDRange
import lift.arithmetic.{ArithExpr, Cst}

object ExpressionFilter {

    // Default input size for all dimensions to use for filtering, if no input combinations provided
  protected[exploration] val default_input_size = 1024

  // Minimum number of work item per workgroup
  protected[exploration] val min_work_items = 128

  // Maximum number of work item per workgroup
  protected[exploration] val max_work_items = 1024

  // Minimal global grid size
  protected[exploration] val min_grid_size = 8

  // Max amount of private memory allocated (this is not necessarily the number of registers)
  protected[exploration] val max_private_memory = 1024

  // Max static amount of local memory
  protected[exploration] val max_local_memory = 50000

  // Minimum number of workgroups
  protected[exploration] val min_workgroups = 8

  // Maximum number of workgroups
  protected[exploration] val max_workgroups = 10000


  private val logger = Logger(this.getClass)

  object Status extends Enumeration {
    type Status = Value
    val Success,
    TooMuchGlobalMemory,
    TooMuchPrivateMemory,
    TooMuchLocalMemory,
    NotEnoughWorkItems,
    TooManyWorkItems,
    NotEnoughWorkGroups,
    TooManyWorkGroups,
    NotEnoughParallelism,
    InternalException = Value
  }

  import exploration.ExpressionFilter.Status._

  def apply(
    local: ArithExpr,
    global: ArithExpr,
    searchParameters: SearchParameters
  ): Status =
    filterNDRanges(
      (NDRange(local, 1, 1), NDRange(global, 1, 1)),
      searchParameters
    )

  def apply(
    local1: ArithExpr, local2: ArithExpr,
    global1: ArithExpr, global2: ArithExpr,
    searchParameters: SearchParameters
  ): Status =
    filterNDRanges(
      (NDRange(local1, local2, 1), NDRange(global1, global2, 1)),
      searchParameters
    )

  def apply(
    local1: ArithExpr, local2: ArithExpr, local3: ArithExpr,
    global1: ArithExpr, global2: ArithExpr, global3: ArithExpr,
    searchParameters: SearchParameters
  ): Status =
    filterNDRanges(
      (NDRange(local1, local2, local3), NDRange(global1, global2, global3)),
      searchParameters
    )

  def filterNDRanges(
    ranges: (NDRange, NDRange),
    searchParameters: SearchParameters
  ): Status = {
    val local = ranges._1
    val global = ranges._2

    try {
      // Rule out obviously poor choices based on the grid size
      // - minimum size of the entire compute grid
      if (global.numberOfWorkItems < searchParameters.minGridSize) {
        logger.debug(s"Not enough work-items in the grid (${global.numberOfWorkItems} - ${local.toString} ${global.toString})")
        return NotEnoughWorkItems
      }

      if (local.forall(_.isEvaluable)) {

        // - minimum of work-items in a workgroup
        if (local.numberOfWorkItems < searchParameters.minWorkItems) {
          logger.debug(s"Not enough work-items in a group (${local.numberOfWorkItems} - ${local.toString} ${global.toString})")
          return NotEnoughWorkItems
        }

        // - maximum of work-items in a workgroup
        if (local.numberOfWorkItems > searchParameters.maxWorkItems) {
          logger.debug(s"Too many work-items in a group (${local.numberOfWorkItems} - ${local.toString} ${global.toString})")
          return TooManyWorkItems
        }

            val numWorkgroups =
              NDRange.numberOfWorkgroups(global, local)

        // - minimum number of workgroups
        if (numWorkgroups < searchParameters.minWorkgroups) {
          logger.debug(s"Not enough work-groups ($numWorkgroups - ${local.toString} ${global.toString})")
          return NotEnoughWorkGroups
        }

        // - maximum number of workgroups
        if (numWorkgroups > searchParameters.maxWorkgroups){
          logger.debug(s"Too many work-groups ($numWorkgroups - ${local.toString} ${global.toString})")
          return TooManyWorkGroups
        }

      }
      // All good...
      Success

    } catch {
      case t: Throwable =>
        logger.warn("Failed filtering NDRanges", t)
        InternalException
    }
  }

  def apply(
    lambda: Lambda, ranges: (NDRange, NDRange),
    searchParameters: SearchParameters = SearchParameters.createDefault
  ): Status = {
    val local = ranges._1
    val global = ranges._2

    try {

      val memoryAmounts = MemoryAmounts(lambda, local, global)

      val privateMemories = memoryAmounts.getPrivateMemories
      val localMemories = memoryAmounts.getLocalMemories
      val globalMemories = memoryAmounts.getGlobalMemories

      // Check private memory usage and overflow
      val privateAllocSize = privateMemories.map(_.mem.size).fold(Cst(0))(_ + _).eval

      if (privateAllocSize > searchParameters.maxPrivateMemory ||
        privateMemories.exists(_.mem.size.eval <= 0)) {
        logger.debug(s"Too much private memory ($privateAllocSize)")
        return TooMuchPrivateMemory
      }

      // Check local memory usage and overflow
      val localAllocSize = localMemories.map(_.mem.size).fold(Cst(0))(_ + _).eval

      if (localAllocSize > searchParameters.maxLocalMemory ||
        localMemories.exists(_.mem.size.eval <= 0)) {
        logger.debug(s"Too much local memory ($localAllocSize)")
        return TooMuchLocalMemory
      }

      // Check global memory overflow
      if (globalMemories.exists(_.mem.size.eval <= 0)) {
        logger.debug("Too much global memory")
        return TooMuchGlobalMemory
      }

      // in case of global-local size exploration, we already checked these before
      if (!ParameterRewrite.settings.parameterRewriteSettings.exploreNDRange)
        filterNDRanges(ranges, searchParameters)
      else
        Success

    } catch {
      case t: Throwable =>
        logger.warn("Failed filtering Expression", t)
        InternalException
    }
  }
}

