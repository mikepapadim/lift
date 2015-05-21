package benchmarks

import arithmetic.Var
import ir.UserFunDef._
import ir._
import opencl.ir.CompositePatterns._
import opencl.ir._
import org.clapper.argot.ArgotConverters._

class MatrixMultiplication (override val f: Seq[(String, Array[Lambda])])
  extends Benchmark("Matrix Multiplication", Seq(1024, 1024, 1024), f, 0.1f, Array(16, 16, 1)) {

  val tileX = parser.option[Int](List("x", "tileX"), "size",
    "Tile size in the M and N dimension")

  val tileY = parser.option[Int](List("y", "tileY"), "size",
    "Tile size in the K dimension")

  val registerBlockM = parser.option[Int](List("bm", "blockM"), "size",
   "Register blocking factor in M dimension")

  val registerBlockN = parser.option[Int](List("bn", "blockN"), "size",
    "Register blocking factor in N dimension")

  override def runScala(inputs: Any*): Array[Float] = {
    val A = inputs(0).asInstanceOf[Array[Array[Float]]]
    val B = inputs(1).asInstanceOf[Array[Array[Float]]]

    val aCols = A(0).length
    val aRows = A.length
    val bCols = B(0).length
    val res =  Array.ofDim[Float](aRows, bCols)

    @inline def computeRow(row: Int) {
      // while statements are much faster than for statements
      var col = 0
      while(col < bCols) { var i = 0; var sum = 0.0f
        while(i < aCols) {
          sum += A(row)(i) * B(i)(col)
          i += 1
        }

        res(row)(col) = sum
        col += 1
      }
    }

    (0 until aRows).par.foreach( computeRow )

    res.flatten
  }



  override def generateInputs(): Seq[Any] = {
    val inputSizeN = inputSizes()(0)
    val inputSizeM = inputSizes()(1)
    val inputSizeK = inputSizes()(2)

    val matrixA = Array.tabulate(inputSizeK, inputSizeM)((r, c) => (((r * 3 + c * 2) % 10) + 1) * 0.1f)
    val matrixB = Array.tabulate(inputSizeK, inputSizeN)((r, c) => (((r * 3 + c * 2) % 10) + 1) * 0.1f)

    Seq(matrixA, matrixB)
  }

  override def globalSize: Array[Int] = {
    val globalSizes = Array(inputSizes()(0), inputSizes()(1), 1)
    globalSizeOpt.value.copyToArray(globalSizes)
    globalSizes
  }

  override protected def beforeBenchmark() = {
    f(1)._2(0) = MatrixMultiplication.tiled(tileX.value.getOrElse(16))
    f(2)._2(0) = MatrixMultiplication.moreWorkPerThread(tileX.value.getOrElse(16),
      registerBlockM.value.getOrElse(4))
    f(3)._2(0) = MatrixMultiplication.tiledAndBlockedBInnermost(tileX.value.getOrElse(16), tileX.value.getOrElse(16),
      tileY.value.getOrElse(8), registerBlockN.value.getOrElse(4), registerBlockM.value.getOrElse(4))
  }

  override protected def printParams(): Unit = {
    println("Tile size: " + tileX.value.getOrElse(16) + " " + tileY.value.getOrElse(8))
    println("Work per thread: " +registerBlockN.value.getOrElse(4) + " " + registerBlockM.value.getOrElse(4))
  }

  override protected def printResults(time: Double): Unit = {
    val dNumOps = 2.0 * inputSizes()(0).toDouble * inputSizes()(1).toDouble * inputSizes()(2).toDouble
    val gflops = 1.0e-6 * dNumOps/time
    println("THROUGHPUT: " + gflops + " GFlops/s")
  }
}

object MatrixMultiplication {
  val N = Var("N")
  val M = Var("M")
  val K = Var("K")

  val naive = fun(
    ArrayType(ArrayType(Float, K), M),
    ArrayType(ArrayType(Float, N), K),
    (A, B) => {
      MapWrg(fun( Arow =>
        Barrier() o MapLcl(fun( Bcol =>
          toGlobal(MapSeq(id)) o ReduceSeq(fun((acc, y) => multAndSumUp.apply(acc, Get(y, 0), Get(y, 1))), 0.0f) $ Zip(Arow, Bcol)
        )) o Transpose() $ B
      )) $ A
    })

  def tiled(tileSize: Int = 4) = fun(
    ArrayType(ArrayType(Float, K), M),
    ArrayType(ArrayType(Float, N), K),
    (A, B) => {
      // Undo the tiling
      Untile() o
        MapWrg(0)(fun( aRows =>
          MapWrg(1)(fun( bCols =>

            toGlobal(MapLcl(1)(MapLcl(0)(id))) o
              Join() o

              // Multiply all necessary combinations of tiles
              ReduceSeq(fun( (acc, pairOfTiles) =>

                fun(pairOfTiles =>
                  Barrier() o fun(partial => MapLcl(1)(fun(pairOfRows => MapLcl(0)(add) $ Zip(Get(pairOfRows, 0), Get(pairOfRows, 1)))) $ Zip(acc, partial) ) o
                    Map(Join()) o
                    MapLcl(1)( fun(rowA =>
                      MapLcl(0)( fun( colB =>
                        ReduceSeq(fun((acc, y) => multAndSumUp.apply(acc, Get(y, 0), Get(y, 1))), 0.0f) $ Zip(rowA, colB)
                      )) o Transpose() $ Get(pairOfTiles, 1)
                    )) $ Get(pairOfTiles, 0)
                ) o

                  // Copy tiles to local memory
                  fun(pairOfTiles =>
                    Tuple(
                      Barrier() o toLocal(MapLcl(1)(MapLcl(0)(id))) $ Get(pairOfTiles, 0),
                      Barrier() o toLocal(MapLcl(1)(MapLcl(0)(id))) $ Get(pairOfTiles, 1)
                    )) $ pairOfTiles
              )
                , MapLcl(1)(MapLcl(0)(id)) $ Value(0.0f, ArrayType(ArrayType(Float, tileSize), tileSize))
              ) $ Zip(aRows, bCols)

          )) o Transpose() o Tile(tileSize) $ B
          // Tile the matrices
        )) o Tile(tileSize) $ A
    })

  def moreWorkPerThread(tileSize: Int = 8, workPerThread: Int = 4) = fun(
    ArrayType(ArrayType(Float, K), M),
    ArrayType(ArrayType(Float, N), K),
    (A, B) => {
      // Undo the tiling
      Untile() o
        MapWrg(0)(fun( aRows =>
          MapWrg(1)(fun( bCols =>
            Join() o Map(TransposeW()) o
              toGlobal(MapLcl(1)(MapLcl(0)(MapSeq(id)))) o
              Join() o

              // Multiply all necessary combinations of tiles
              ReduceSeq(fun( (acc, pairOfTiles) =>

                fun(pairOfTiles =>
                  Barrier() o fun(partial =>
                    MapLcl(1)(fun(pairOfRows =>
                      MapLcl(0)(fun(x => MapSeq(add) $ Zip(Get(x, 0), Get(x, 1))
                      )) $ Zip(Get(pairOfRows, 0), Get(pairOfRows, 1))
                    )) $ Zip(acc, partial)
                  ) o

                    MapLcl(1)( fun(rowsA =>
                      MapLcl(0)( fun( colB =>
                        Join() o ReduceSeq(fun((acc, rowElemPair) =>
                          MapSeq(add) o fun(rowElemPair =>
                            Zip(
                              toPrivate(MapSeq(fun(a => mult.apply(a, Get(rowElemPair, 1)))
                              )) $ Get(rowElemPair, 0),
                              acc
                            )
                          ) $ rowElemPair
                        ), toPrivate(MapSeq(id)) $ Value("0.0f", ArrayType(Float, workPerThread))
                        ) $ Zip(Transpose() $ rowsA, colB)
                      )) o Transpose() $ Get(pairOfTiles, 1)
                    )) o Split(workPerThread) $ Get(pairOfTiles, 0)

                ) o

                  // Copy tiles to local memory
                  fun(pairOfTiles =>
                    Tuple(
                      Join() o Barrier() o toLocal(MapSeq(MapLcl(1)(MapLcl(0)(id))))
                        o Split(tileSize/workPerThread) $ Get(pairOfTiles, 0),
                      Join() o Barrier() o toLocal(MapSeq(MapLcl(1)(MapLcl(0)(id))))
                        o Split(tileSize/workPerThread) $ Get(pairOfTiles, 1)
                    )) $ pairOfTiles
              )
                , MapLcl(1)(MapLcl(0)(MapSeq(id))) $ Value(0.0f,
                  ArrayType(ArrayType(ArrayType(Float, workPerThread), tileSize), tileSize/workPerThread))
              ) $ Zip(aRows, bCols)

          )) o Transpose() o Tile(tileSize) $ B
          // Tile the matrices
        )) o Tile(tileSize) $ A
    })

  def tiledAndBlockedBInnermost(tileSizeN:Int, tileSizeM:Int, tileSizeK: Int,
                                  workPerThreadN:Int, workPerThreadM:Int) = fun(
    ArrayType(ArrayType(Float, M), K), // Transposed
    ArrayType(ArrayType(Float, N), K),
    (A, B) => {
      // Undo the tiling
      Untile() o
        MapWrg(0)(fun( aRows =>
          MapWrg(1)(fun( bCols =>

            Map(Scatter(IndexFunction.reorderStride(tileSizeM/workPerThreadM))) o Join() o
              Map(TransposeW() o Join() o Map(TransposeW())) o

              toGlobal(MapLcl(1)(MapLcl(0)(MapSeq(MapSeq(id))))) o

              Join() o

              // Multiply all necessary combinations of tiles
              ReduceSeq(fun( (acc, pairOfTiles) =>

                fun(pairOfTiles =>
                  Barrier() o

                    MapLcl(1)( fun(rowsA =>
                      MapLcl(0)( fun( colsB =>
                        Join() o ReduceSeq(fun((acc, rowElemPair) =>
                          MapSeq(fun(pair => MapSeq(add) $ Zip(Get(pair, 0), Get(pair, 1)))) o
                            fun(rowElemPair =>
                              Zip(
                                Join() o toPrivate(MapSeq(MapSeq(
                                  fun(aArray => MapSeq(fun(b =>
                                    mult.apply(aArray, b)
                                  )) $ Get(rowElemPair, 1))) o toPrivate(MapSeq(id))
                                )) o Split(1) $ Get(rowElemPair, 0),
                                acc
                              )
                            ) o fun(rowElemPair =>
                            Tuple(
                              Get(rowElemPair, 0),
                              toPrivate(MapSeq(id)) $ Get(rowElemPair, 1)
                            )) $ rowElemPair
                        ), Get(colsB, 1)
                        ) $ Zip(Transpose() $ Get(rowsA, 0), Transpose() $ Get(colsB, 0))

                      )) $ Zip(Split(workPerThreadM) o ReorderStride(tileSizeM/workPerThreadM) o Transpose() $ Get(pairOfTiles, 1), Get(rowsA, 1))
                    ))  $ Zip(Split(workPerThreadN) o Transpose() $ Get(pairOfTiles, 0), acc)

                ) o

                  // Copy tiles to local memory
                  Unzip() o Barrier() o toLocal(MapLcl(1)(fun(pair =>
                  fun(pair => Tuple(Join() $ Get(pair, 0), Join() $ Get(pair, 1))) o
                    Unzip() o MapLcl(0)(fun( pair =>
                    Tuple(MapSeq(id) $ Get(pair, 0), MapSeq(id) $ Get(pair, 1))
                  )) $ Zip(Split(1) $ Get(pair, 0), Split(1) $ Get(pair, 1))
                ))) $ Zip(Get(pairOfTiles, 0), Get(pairOfTiles, 1))
              )
                , MapLcl(1)(MapLcl(0)(MapSeq(MapSeq(id)))) $ Value(0.0f,
                  ArrayType(ArrayType(ArrayType(ArrayType(Float, workPerThreadM), workPerThreadN), tileSizeM/workPerThreadM), tileSizeN/workPerThreadN))
              ) $ Zip(aRows, bCols)

            // Tile the matrices
          )) o Transpose() o Tile(tileSizeK, tileSizeN) $ B
        )) o Transpose() o Tile(tileSizeK, tileSizeM) $ A
    })

  def apply() = new MatrixMultiplication(
    Seq(("naive", Array[Lambda](naive)),
      ("tiled", Array[Lambda](tiled(16))),
      ("moreWorkPerThread", Array[Lambda](moreWorkPerThread(16, 4))),
      ("tiledAndBlockedBInnermost", Array[Lambda](tiledAndBlockedBInnermost(16, 16, 8, 4, 4)))
    ))


  def main(args: Array[String]): Unit = {
    MatrixMultiplication().run(args)
  }
}