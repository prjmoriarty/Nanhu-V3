/***************************************************************************************
 * Copyright (c) 2020-2023 Institute of Computing Technology, Chinese Academy of Sciences
 *
 * XiangShan is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/
/***************************************************************************************
 * Author: Liang Sen
 * E-mail: liangsen20z@ict.ac.cn
 * Date: 2023-06-19
 ****************************************************************************************/
package xiangshan.vector.vexecute.vissue

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.experimental.ChiselAnnotation
import chisel3.util._
import firrtl.annotations.Annotation
import firrtl.transforms.NoDedupAnnotation
import xiangshan.{MicroOp, Redirect, XSModule}
import xs.utils.{CircularQueuePtr, HasCircularQueuePtrHelper, LogicShiftRight}
sealed class TwoEntryQueuePtr extends CircularQueuePtr[TwoEntryQueuePtr](entries = 2) with HasCircularQueuePtrHelper

class VDecoupledPipeline(implicit p: Parameters) extends XSModule{
  val io = IO(new Bundle{
    val redirect = Input(Valid(new Redirect))
    val enq = Flipped(DecoupledIO(new MicroOp))
    val deq = DecoupledIO(new MicroOp)
  })
    val mem = Reg(Vec(2, new MicroOp))
    val enqPtr = RegInit(0.U.asTypeOf(new TwoEntryQueuePtr))
    val deqPtr = RegInit(0.U.asTypeOf(new TwoEntryQueuePtr))
    val full = enqPtr.value === deqPtr.value && enqPtr.flag =/= deqPtr.flag
    val empty = enqPtr.value === deqPtr.value && enqPtr.flag === deqPtr.flag
    val enqFire = io.enq.fire
    val deqFire = io.deq.fire

  private val kills = Wire(Vec(2, Bool()))
  kills.zip(mem).foreach({case(k,u) => k := u.robIdx.needFlush(io.redirect)})

  io.enq.ready := !full
  io.deq.valid := !empty && !kills(deqPtr.value)
  private val outData = mem(deqPtr.value)
  io.deq.bits := outData

  when(full && kills((enqPtr - 1.U).value)){
    enqPtr := enqPtr - 1.U
  }.elsewhen(enqFire){
    mem(enqPtr.value) := io.enq.bits
    enqPtr := enqPtr + 1.U
  }
  when(deqFire || (!empty && kills(deqPtr.value))) {
    deqPtr := deqPtr + 1.U
  }
}