package xiangshan.backend.exu

import chisel3._
import chisel3.util._
import utils._
import xiangshan.backend.fu.FunctionUnit
import xiangshan.backend.fu.FunctionUnit.fmiscSel
import xiangshan.backend.fu.fpu.FPUOpType._
import xiangshan.backend.fu.fpu._

class FmiscExeUnit extends Exu(
  exuName = "FmiscExeUnit",
  fuGen = {
    Seq[(() => FPUSubModule, FPUSubModule => Bool)](
      (FunctionUnit.fcmp _, fmiscSel(FU_FCMP)),
      (FunctionUnit.fmv _, fmiscSel(FU_FMV)),
      (FunctionUnit.f2i _, fmiscSel(FU_F2I)),
      (FunctionUnit.f32toF64 _, fmiscSel(FU_S2D)),
      (FunctionUnit.f64toF32 _, fmiscSel(FU_D2S)),
      (FunctionUnit.fdivSqrt _, fmiscSel(FU_DIVSQRT))
    )
  },
  wbIntPriority = Int.MaxValue,
  wbFpPriority = 1
) {

  val frm = IO(Input(UInt(3.W)))

  val fcmp :: fmv :: f2i :: f32toF64 :: f64toF32 :: fdivSqrt :: Nil = supportedFunctionUnits
  val toFpUnits = Seq(fcmp, f32toF64, f64toF32, fdivSqrt)
  val toIntUnits = Seq(fmv, f2i)

  val input = io.fromFp
  val fuOp = input.bits.uop.ctrl.fuOpType
  assert(fuOp.getWidth == 7) // when fuOp's WIDTH change, here must change too
  val fu = fuOp.head(4)
  val op = fuOp.tail(4)
  val isRVF = input.bits.uop.ctrl.isRVF
  val instr_rm = input.bits.uop.cf.instr(14, 12)
  val (src1, src2) = (input.bits.src1, input.bits.src2)

  supportedFunctionUnits.foreach { module =>
    module.io.in.bits.src(0) := Mux(
      (isRVF && fuOp =/= d2s && fuOp =/= fmv_f2i) || fuOp === s2d,
      unboxF64ToF32(src1),
      src1
    )
    if (module.cfg.srcCnt > 1) {
      module.io.in.bits.src(1) := Mux(isRVF, unboxF64ToF32(src2), src2)
    }
    module.rm := Mux(instr_rm =/= 7.U, instr_rm, frm)
  }

  io.toFp.bits.fflags := Mux1H(fpArb.io.in.zip(toFpUnits).map(
    x => x._1.fire() -> x._2.fflags
  ))
  val fpOutCtrl = io.toFp.bits.uop.ctrl
  io.toFp.bits.data := Mux(fpOutCtrl.isRVF,
    boxF32ToF64(fpArb.io.out.bits.data),
    fpArb.io.out.bits.data
  )
  val intOutCtrl = io.toInt.bits.uop.ctrl
  io.toInt.bits.data := Mux(
    (intOutCtrl.isRVF && intOutCtrl.fuOpType === fmv_i2f) ||
      intOutCtrl.fuOpType === f2w ||
      intOutCtrl.fuOpType === f2wu,
    SignExt(intArb.io.out.bits.data(31, 0), XLEN),
    intArb.io.out.bits.data
  )
  io.toInt.bits.fflags := Mux1H(intArb.io.in.zip(toIntUnits).map(
    x => x._1.fire() -> x._2.fflags
  ))
}
