package exploration

import java.io.{File, IOException}
import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.scalalogging.Logger
import exploration.ExpressionFilter.Status.Success
import ir.ast.{Expr, FunCall, Lambda}
import ir.{Type, TypeChecker}
import lift.arithmetic._
import opencl.executor.Eval
import opencl.generator.{NDRange, OpenCLGenerator}
import opencl.ir.pattern._
import org.clapper.argot.ArgotConverters._
import org.clapper.argot._
import rewriting.utils.Utils

import scala.collection.immutable.Map
import scala.io.Source
import scala.sys.process._
import scala.util.Random

/**
  * This main currently runs a parameter space exploration over the
  * serialized low level expressions.
  */
object GenericKernelPrinter {

  private val logger = Logger(this.getClass)

  private var topFolder = ""

  private val parser = new ArgotParser("ParameterRewrite")

  parser.flag[Boolean](List("h", "help"),
    "Show this message.") {
    (sValue, _) =>
      parser.usage()
      sValue
  }

  private val input = parser.parameter[String]("input",
    "Input file containing the lambda to use for rewriting",
    optional = false) {
    (s, _) =>
      val file = new File(s)
      if (!file.exists)
        parser.usage("Input file \"" + s + "\" does not exist")
      s
  }

  private[exploration] val defaultExploreNDRange = false
  private[exploration] val defaultSampleNDRange = -1
  private[exploration] val defaultDisableNDRangeInjection = false
  private[exploration] val defaultSequential = false
  private[exploration] val defaultGenerateScala = false

  protected[exploration] val exploreNDRange = parser.flag[Boolean](List("e", "exploreNDRange"),
    s"Explore global and local sizes instead of inferring (default: $defaultExploreNDRange)")

  protected[exploration] val sampleNDRange = parser.option[Int](List("sampleNDRange"), "n",
    s"Randomly sample n combinations of global and local sizes (requires 'explore') (default: $defaultSampleNDRange)")

  protected[exploration] val disableNDRangeInjection = parser.flag[Boolean](List("disableNDRangeInjection"),
    s"Don't inject NDRanges while compiling the OpenCL Kernel (default: $defaultDisableNDRangeInjection)")

  protected[exploration] val sequential = parser.flag[Boolean](List("s", "seq", "sequential"),
    s"Don't execute in parallel (default: $defaultSequential)")

  protected[exploration] val generateScala = parser.flag[Boolean](List("generate-scala"),
    s"Generate lambdas in Scala as well as in OpenCL (default: $defaultGenerateScala)")


  private val settingsFile = parser.option[String](List("f", "file"), "name",
    "The settings file to use."
  ) {
    (s, _) =>
      val file = new File(s)
      if (!file.exists)
        parser.usage(s"Settings file $file doesn't exist.")
      s
  }

  private[exploration] var settings = Settings()

  private var lambdaFilename = ""

  def main(args: Array[String]): Unit = {

    try {

      parser.parse(args)
      settings = ParseSettings(settingsFile.value)
      val config = settings.parameterRewriteSettings
      if (!config.exploreNDRange && config.sampleNDRange > 0)
        throw new RuntimeException("'sample' is defined without enabling 'explore'")

      logger.info(s"Arguments: ${args.mkString(" ")}")
      val inputArgument = input.value.get

      topFolder = Paths.get(inputArgument).toString

      lambdaFilename = topFolder + "Scala/lambdaFile"

      if (config.generateScala) {
        val f = new File(lambdaFilename)
        if (f.exists()) {
          f.delete()
        } else {
          s"mkdir -p ${topFolder}Scala".!
        }
      }


      logger.info(s"Arguments: ${args.mkString(" ")}")
      logger.info(s"Settings:\n$settings")

      // list all the high level expression
      val all_files = Source.fromFile(s"$topFolder/index").getLines().toList
      val highLevelCount = all_files.size

      val parentFolder = Paths.get(topFolder).toAbsolutePath.getParent

      var expr_counter = 0
      all_files.foreach(filename => {

        val fullFilename = parentFolder + "/" + filename

        if (Files.exists(Paths.get(fullFilename))) {
          val high_level_hash = filename.split("/").last
          expr_counter = expr_counter + 1
          println(s"High-level expression : $expr_counter / $highLevelCount")

          try {

            val high_level_expr_orig = readLambdaFromFile(fullFilename)

            val vars = high_level_expr_orig.getVarsInParams()

            val combinations = settings.inputCombinations

            val st =
              if (combinations.isDefined &&
                combinations.get.head.length == vars.length)
                (vars: Seq[ArithExpr], combinations.get.head).zipped.toMap
              else
                createValueMap(high_level_expr_orig)

            val sizesForFilter = st.values.toSeq

            val high_level_expr = replaceInputTypes(high_level_expr_orig, st)

            TypeChecker(high_level_expr)

            val loweredIndex = s"${topFolder}Lower/$high_level_hash/index"
            if (Files.exists(Paths.get(loweredIndex))) {

              val lowLevelExprList = Source.fromFile(loweredIndex).getLines().toList

              val lowLevelCounter = new AtomicInteger()
              val lowLevelCount = lowLevelExprList.size
              val propagationCounter = new AtomicInteger()
              val kernelCounter = new AtomicInteger()
              println(s"Found $lowLevelCount low level expressions")

              val parList = if (config.sequential) lowLevelExprList else lowLevelExprList.par

              parList.foreach(low_level_filename => {

                try {

                  val low_level_hash = low_level_filename.split("/").last
                  val fullLowLevelFilename = parentFolder + "/" + low_level_filename
                  val low_level_str = readFromFile(fullLowLevelFilename)
                  val low_level_factory = Eval.getMethod(low_level_str)

                  lowLevelCounter.incrementAndGet()

                  def countSubstring( str:String, substr:String ) = substr.r.findAllMatchIn(str).length
                  val number = countSubstring(low_level_str, "variables") - 3
                  val vars = Seq.fill(number)(TuningParameter())

                  val expr = low_level_factory(/*sizesForFilter ++*/ Seq(Var("M"), Var("N")) ++ vars)
                      TypeChecker(expr)

                  val kernel = opencl.executor.Compile(expr)
                  if(low_level_hash == "974323ee359506c482e957a975b7837f54f1e0f25b23b2d0b1fa1b061aacfc6a") {
                    println(kernel)
                  }

                } catch {
                  case t: Throwable =>
                    // Failed reading file or similar.
                    logger.warn(t.toString)
                } //&& substitutionCount < 800000
              })
              println(s"\nGenerated $kernelCounter kernels")
            }
          } catch {
            case t: Throwable =>
              logger.warn(t.toString)
          }
        }
      })
    } catch {
      case io: IOException =>
        logger.error(io.toString)
      case e: ArgotUsageException =>
        println(e.message)
    }
  }

  def saveScala(expressions: Seq[(Lambda, Seq[ArithExpr], (NDRange, NDRange))], hashes: Seq[Option[String]]): Unit = {
    val filename = lambdaFilename
    val file = scala.tools.nsc.io.File(filename)

    (expressions, hashes).zipped.foreach((f, hash) => {

      try {
        val stringRep = "{ " + Utils.dumpLambdaToString(f._1).replace("\n", "; ") + "}"

        val sha256 = hash.get

        synchronized {
          file.appendAll("(\"" + sha256 + "\",  " + s"Array($stringRep)) ,\n")
        }
      } catch {
        case t: Throwable =>
          logger.warn(t.toString)
      }
    })
  }

  def readFromFile(filename: String) =
    Source.fromFile(filename).mkString

  def readLambdaFromFile(filename: String) =
    Eval(readFromFile(filename))

  def createValueMap(lambda: Lambda, sizes: Seq[ArithExpr] = Seq()): Map[ArithExpr, ArithExpr] = {
    val vars = lambda.getVarsInParams()

    val actualSizes: Seq[ArithExpr] =
      if (sizes.isEmpty) Seq.fill(vars.length)(settings.searchParameters.defaultInputSize)
      else sizes

    (vars, actualSizes).zipped.toMap
  }

  def replaceInputTypes(lambda: Lambda, st: Map[ArithExpr, ArithExpr]): Lambda = {
    val tunable_nodes = Utils.findTunableNodes(lambda).reverse
    lambda.params.foreach(p => p.t = Type.substitute(p.t, st))
    Utils.quickAndDirtySubstitution(st, tunable_nodes, lambda)
  }

  private def computeValidNDRanges(expr: Lambda): Seq[(NDRange, NDRange)] = {
    var usedDimensions: Set[Int] = Set()
    Expr.visit(expr.body, {
      case FunCall(MapGlb(dim, _), _) =>
        usedDimensions += dim

      case FunCall(MapLcl(dim, _), _) =>
        usedDimensions += dim

      case FunCall(MapWrg(dim, _), _) =>
        usedDimensions += dim

      case FunCall(MapAtomLcl(dim, _, _), _) =>
        usedDimensions += dim

      case FunCall(MapAtomWrg(dim, _, _), _) =>
        usedDimensions += dim

      case _ =>
    }, (_) => Unit)
    val nDRangeDim = usedDimensions.max + 1

    logger.debug(s"computing ${nDRangeDim}D NDRanges")

    // hardcoded highest power of two = 8192
    val pow2 = Seq.tabulate(14)(x => scala.math.pow(2, x).toInt)
    val localGlobalCombinations: Seq[(ArithExpr, ArithExpr)] = (for {
      local <- pow2
      global <- pow2
      if local <= global
    } yield (local, global)).map { case (l, g) => (Cst(l), Cst(g)) }

    nDRangeDim match {
      case 1 => for {
        x <- localGlobalCombinations
        if ExpressionFilter(x._1, x._2, settings.searchParameters) == Success
      } yield (NDRange(x._1, 1, 1), NDRange(x._2, 1, 1))

      case 2 => for {
        x: (ArithExpr, ArithExpr) <- localGlobalCombinations
        y: (ArithExpr, ArithExpr) <- localGlobalCombinations
        if ExpressionFilter(x._1, y._1, x._2, y._2, settings.searchParameters) == Success
      } yield (NDRange(x._1, y._1, 1), NDRange(x._2, y._2, 1))

      case 3 => for {
        x <- localGlobalCombinations
        y <- localGlobalCombinations
        z <- localGlobalCombinations
        if ExpressionFilter(x._1, y._1, z._1, x._2, y._2, z._2, settings.searchParameters) == Success
      } yield (NDRange(x._1, y._1, z._1), NDRange(x._2, y._2, z._2))

      case _ => throw new RuntimeException("Could not pre-compute NDRanges for exploration")
    }
  }
}