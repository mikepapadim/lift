package rewriting.utils

import ir._
import ir.ast._
import opencl.generator.NotPrintableExpression
import opencl.ir.ast.OpenCLBuiltInFun
import opencl.ir.pattern._

object ScalaPrinter {
  def apply(expr: Expr): String = {
    expr match {
      case funCall: FunCall => s"FunCall(${apply(funCall.f)}, ${funCall.args.map(apply).mkString(", ")})"
      case value: Value => s"""Value("${value.value}", ${apply(value.t)})"""
      case param: Param => "p_" + param.hashCode()
      case _ => expr.toString
    }
  }

  def apply(funDecl: FunDecl): String = {
    funDecl match {
      case lambda: Let => s"new Let((${lambda.params.map(apply).mkString(", ")}) => ${apply(lambda.body)})"
      case lambda: Lambda => s"fun((${lambda.params.map(apply).mkString(", ")}) => ${apply(lambda.body)})"
      case map: Map => s"Map(${apply(map.f)})"
      case mapSeq: MapSeq => s"MapSeq(${apply(mapSeq.f)})"
      case mapWrg: MapWrg => s"MapWrg(${mapWrg.dim})(${apply(mapWrg.f)})"
      case mapLcl: MapLcl => s"MapLcl(${mapLcl.dim})(${apply(mapLcl.f)})"
      case mapWrg: MapAtomWrg => s"MapAtomWrg(${mapWrg.dim})(${apply(mapWrg.f)})"
      case mapLcl: MapAtomLcl => s"MapAtomLcl(${mapLcl.dim})(${apply(mapLcl.f)})"
      case mapGlb: MapGlb => s"MapGlb(${mapGlb.dim})(${apply(mapGlb.f)})"
      case reduceSeq: ReduceSeq => s"ReduceSeq(${apply(reduceSeq.f)})"
      case scanplus: ScanPlus => s"ScanPlus(${apply(scanplus.f)})"
      case reduce: Reduce => s"Reduce(${apply(reduce.f)})"
      case reduce: PartRed => s"PartRed(${apply(reduce.f)})"
      case toGlobal: toGlobal => s"toGlobal(${apply(toGlobal.f)})"
      case toLocal: toLocal => s"toLocal(${apply(toLocal.f)})"
      case toPrivate: toPrivate => s"toPrivate(${apply(toPrivate.f)})"
      case bsearch: BSearch => s"BSearch(${apply(bsearch.f)})"
      case lsearch: LSearch => s"LSearch(${apply(lsearch.f)})"
      case vec: VectorizeUserFun => s"VectorizeUserFun(${vec.n},${vec.userFun})"
      case _ => funDecl.toString
    }
  }

  def apply(t: Type): String = {
    t match {
      case opencl.ir.Float => "Float"
      case opencl.ir.Int => "Int"
      case opencl.ir.Double => "Double"
      case opencl.ir.Bool => "Bool"
      case TupleType(tt@_*) => s"TupleType(${tt.map(apply).mkString(", ")})"
      case VectorType(elemT, len) => s"VectorType(${apply(elemT)}, $len)"
      case ArrayType(elemT, len) => s"ArrayType(${apply(elemT)}, $len)"
      case NoType => throw new NotPrintableExpression(s"Can not print NoType")
      case UndefType => throw new NotPrintableExpression(s"Can not print UndefType")
      case s: ScalarType => throw new NotPrintableExpression(s"Can not print ScalaType: $s")
    }
  }

  def apply(uf: UserFun): String = {
    val name = "\"" + uf.name + "\""
    val paramNames = uf.paramNames.map("\"" + _ + "\"").mkString(", ")
    val inTs = uf.inTs.map(apply).mkString(", ")
    val outT = apply(uf.outT)

    val body = "\"\"\"" + uf.body.split("\n").map("|" + _).mkString("\n") + "\"\"\".stripMargin"

    s"val ${uf.name} = UserFun($name, Array($paramNames), $body, Seq($inTs), $outT)"
  }

  def apply(uf: OpenCLBuiltInFun): String = ""
}
