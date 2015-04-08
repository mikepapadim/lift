package ir.view

import arithmetic.{Cst, ArithExpr}
import ir._
import opencl.ir._

object OutputView {

  def apply(expr: Expr): Unit = visitAndBuildViews(expr, View(expr.t, ""))

  def visitAndBuildViews(expr: Expr, writeView: View): View = {
    expr match {
      case pr: ParamReference => pr.p.view.get(pr.i)
      case p: Param => p.view
      case call: FunCall => buildViewFunCall(call, writeView)
    }
  }

  private def buildViewFunCall(call: FunCall, writeView: View): View = {
    call match {
      case call: MapCall => buildViewMapCall(call, writeView)
      case call: ReduceCall => buildViewReduceCall(call, writeView)
      case call: FunCall =>
        call.f match {
          case l: Lambda => buildViewLambda(l, call, writeView)
          case cf: CompFunDef => buildViewCompFunDef(cf, writeView)
          case Split(n) => buildViewSplit(n, writeView)
          case _: Join => buildViewJoin(call, writeView)
          case uf: UserFunDef => buildViewUserFun(writeView, call)
          case s: Scatter => buildViewScatter(s, call, writeView)
          case g: Gather => buildViewGather(g, call, writeView)
          case tL: toLocal => buildViewToLocal(tL, writeView)
          case tG: toGlobal => buildViewToGlobal(tG, writeView)
          case i: Iterate => buildViewIterate(i, call, writeView)
          case tw: TransposeW => buildViewTransposeW(tw, call, writeView)
          case asVector(n) => buildViewAsVector(n, writeView)
          case _: asScalar => buildViewAsScalar(call, writeView)
          case Zip(_) | Tuple(_) => buildViewZipTuple(call, writeView)
          //case uz: Unzip =>
          case _ => writeView
        }
    }
  }

  private def buildViewUserFun(writeView: View, call: FunCall): View = {
    call.view = writeView
    writeView
  }

  private def buildViewZipTuple(call: FunCall, writeView: View): View = {
    call.args.map((expr: Expr) => visitAndBuildViews(expr, writeView))
    writeView
  }

  private def buildViewIterate(i: Iterate, call: FunCall, writeView: View): View = {
    visitAndBuildViews(i.f.body, writeView)
    View.initialiseNewView(call.t, call.outputDepth)
  }

  private def buildViewToGlobal(tG: toGlobal, writeView: View): View = {
    visitAndBuildViews(tG.f.body, writeView)
  }

  private def buildViewToLocal(tL: toLocal, writeView: View): View = {
    visitAndBuildViews(tL.f.body, writeView)
  }

  private def buildViewMapCall(call: MapCall, writeView: View): View = {
    // traverse into call.f
    val innerView = visitAndBuildViews(call.f.f.body, writeView.access(call.loopVar))

    if (call.isConcrete) {
      // create fresh view for following function
      View.initialiseNewView(call.arg.t, call.outputDepth, call.mem.variable.name)
    } else { // call.isAbstract and return input map view
      new ViewMap(innerView, call.loopVar, call.arg.t)
    }
  }

  private def buildViewReduceCall(call: ReduceCall, writeView: View): View = {
    // traverse into call.f
    visitAndBuildViews(call.f.f.body, writeView.access(Cst(0)))
    // create fresh input view for following function
    View.initialiseNewView(call.arg1.t, call.outputDepth, call.mem.variable.name)
  }

  private def buildViewLambda(l: Lambda, call: FunCall, writeView: View): View = {
    visitAndBuildViews(l.body, writeView)
  }

  private def buildViewCompFunDef(cf: CompFunDef, writeView: View): View = {
    cf.funs.foldLeft(writeView)((v, f) => visitAndBuildViews(f.body, v))
  }

  private def buildViewJoin(call: FunCall, writeView: View): View = {
    val chunkSize = call.argsType match {
      case ArrayType(ArrayType(_, n), _) => n
      case _ => throw new IllegalArgumentException("PANIC, expected 2D array, found " + call.argsType)
    }

    writeView.split(chunkSize)
  }

  private def buildViewSplit(n: ArithExpr, writeView: View): View = {
    writeView.join(n)
  }

  private def buildViewAsVector(n: ArithExpr, writeView: View): View = {
    writeView.asScalar()
  }

  private def buildViewAsScalar(call: FunCall, writeView: View): View = {
    call.args(0).t match {
      case ArrayType(VectorType(_, n), _) => writeView.asVector(n)
      case _ => throw new IllegalArgumentException
    }
  }

  private def buildViewTransposeW(tw: TransposeW, call: FunCall, writeView: View): View = {
    call.t match {
      case ArrayType(ArrayType(typ, m), n) =>
        writeView.
          join(m).
          reorder((i:ArithExpr) => { IndexFunction.transpose(i, ArrayType(ArrayType(typ, n), m)) }).
          split(n)
    }
  }

  private def buildViewGather(gather: Gather, call: FunCall, writeView: View): View = {
    visitAndBuildViews(gather.f.body, writeView)
  }

  private def buildViewScatter(scatter: Scatter, call: FunCall, writeView: View): View = {
    val reordered = writeView.reorder( (i:ArithExpr) => { scatter.idx.f(i, call.t) } )
    visitAndBuildViews(scatter.f.body, reordered)
  }
}