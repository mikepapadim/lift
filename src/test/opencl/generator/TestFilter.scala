package opencl.generator

import ir._
import ir.ast._
import lift.arithmetic.SizeVar
import opencl.executor.{Execute, TestWithExecutor}
import opencl.ir._
import opencl.ir.pattern._
import org.junit.Assert._
import org.junit.Test


object TestFilter extends TestWithExecutor

class TestFilter {

  @Test def filter(): Unit = {
    val inputSize = 256
    val inputData = Array.fill(inputSize)(util.Random.nextInt(5).toFloat)
    val ids = Array.range(0, inputSize/2).map(_*2)

    val gold = ids.map(inputData(_))

    val N = SizeVar("N")
    val M = SizeVar("M")

    val compFun = fun(
      ArrayTypeWSWC(Float, N),
      ArrayTypeWSWC(Int, M),
      (input, ids) =>
        MapGlb(id) $ Filter(input, ids)
    )

    val (output, runtime) = Execute(inputSize/2)[Array[Float]](compFun, inputData, ids)
    assertArrayEquals(gold, output, 0.0f)

    println("output(0) = " + output(0))
    println("runtime = " + runtime)
  }

  @Test def splitAfterFilter(): Unit = {
    val inputSize = 1024
    val inputData = Array.fill(inputSize)(util.Random.nextInt(5).toFloat)
    val ids = Array.range(0, inputSize/2).map(_*2)

    val gold = ids.map(inputData(_))

    val N = SizeVar("N")
    val M = SizeVar("M")

    val compFun = fun(
      ArrayTypeWSWC(Float, N),
      ArrayTypeWSWC(Int, M),
      (input, ids) =>
        Join() o MapWrg( MapLcl(id)) o Split(4) $ Filter(input, ids)
    )

    val (output, runtime) = Execute(inputSize/2)[Array[Float]](compFun, inputData, ids)
    assertArrayEquals(gold, output, 0.0f)

    println("output(0) = " + output(0))
    println("runtime = " + runtime)
  }

  @Test def filterAfterSplit(): Unit = {
    val inputSize = 1024
    val inputData = Array.fill(inputSize)(util.Random.nextInt(5).toFloat)
    val ids = Array.range(0, 2).map(_*2)

    val gold = inputData.grouped(4).map(row => ids.map(row(_))).flatten.toArray

    val N = SizeVar("N")
    val M = SizeVar("M")

    val compFun = fun(
      ArrayTypeWSWC(Float, N),
      ArrayTypeWSWC(Int, M),
      (input, ids) =>
        Join() o MapWrg(fun( x =>  MapLcl(id) $ Filter(x, ids))) o Split(4) $ input
    )

    val (output, runtime) = Execute(inputSize/2)[Array[Float]](compFun, inputData, ids)
    assertArrayEquals(gold, output, 0.0f)

    println("output(0) = " + output(0))
    println("runtime = " + runtime)
  }

  @Test def filterOuterDim(): Unit = {
    val inputSize = 1024
    val inputData = Array.fill(inputSize, inputSize)(util.Random.nextInt(5).toFloat)
    val ids = Array.range(0, inputSize/2).map(_*2)

    val gold = ids.flatMap(inputData(_))

    val N = SizeVar("N")
    val M = SizeVar("M")

    val compFun = fun(
      ArrayTypeWSWC(ArrayTypeWSWC(Float, N), N),
      ArrayTypeWSWC(Int, M),
      (input, ids) =>
        MapWrg( MapLcl(id)) $ Filter(input, ids)
    )

    val (output, runtime) = Execute(inputSize/2)[Array[Float]](compFun, inputData, ids)
    assertArrayEquals(gold, output, 0.0f)

    println("output(0) = " + output(0))
    println("runtime = " + runtime)
  }

  @Test def filterInnerDim(): Unit = {
    val inputSize = 1024
    val inputData = Array.fill(inputSize, inputSize)(util.Random.nextInt(5).toFloat)
    val ids = Array.range(0, inputSize/2).map(_*2)

    val gold = inputData.flatMap(row => ids.map(row(_)))

    val N = SizeVar("N")
    val M = SizeVar("M")

    val compFun = fun(
      ArrayTypeWSWC(ArrayTypeWSWC(Float, N), N),
      ArrayTypeWSWC(Int, M),
      (input, ids) =>
        MapWrg(fun(x =>  MapLcl(id) $ Filter(x, ids))) $ input
    )

    val (output, runtime) = Execute(inputSize/2)[Array[Float]](compFun, inputData, ids)
    assertArrayEquals(gold, output, 0.0f)

    println("output(0) = " + output(0))
    println("runtime = " + runtime)
  }

}
