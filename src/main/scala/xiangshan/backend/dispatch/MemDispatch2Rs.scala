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
package xiangshan.backend.dispatch
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import xiangshan.mem.{LsqEnqCtrl, LsqEnqIO, LsqVecDeqIO}
import xiangshan.{FuType, MicroOp, Redirect, XSModule}

class MemDispatch2Rs(implicit p: Parameters) extends XSModule{
  val io = IO(new Bundle{
    val in: Vec[DecoupledIO[MicroOp]]   = Vec(coreParams.rsBankNum, Flipped(DecoupledIO(new MicroOp)))
    val out: Vec[DecoupledIO[MicroOp]]  = Vec(coreParams.rsBankNum, DecoupledIO(new MicroOp))
    val lcommit: UInt                   = Input(UInt(log2Up(CommitWidth + 1).W))
    val scommit: UInt                   = Input(UInt(log2Up(CommitWidth + 1).W))
    val lqCancelCnt: UInt               = Input(UInt(log2Up(LoadQueueSize + 1).W))
    val sqCancelCnt: UInt               = Input(UInt(log2Up(StoreQueueSize + 1).W))
    val enqLsq: LsqEnqIO                = Flipped(new LsqEnqIO)
    val redirect: Valid[Redirect]       = Flipped(ValidIO(new Redirect))
    val lsqVecDeqCnt = Input(new LsqVecDeqIO)
  })

  private val is_blocked = WireInit(VecInit(Seq.fill(io.in.length)(false.B)))
  private val lsqCtrl = Module(new LsqEnqCtrl)
  lsqCtrl.io.redirect := io.redirect
  lsqCtrl.io.lcommit := io.lcommit
  lsqCtrl.io.scommit := io.scommit
  lsqCtrl.io.lqCancelCnt := io.lqCancelCnt
  lsqCtrl.io.sqCancelCnt := io.sqCancelCnt
  io.enqLsq <> lsqCtrl.io.enqLsq

  lsqCtrl.io.lsqVecDeqIO <> io.lsqVecDeqCnt

  private val enqCtrl = lsqCtrl.io.enq
  private val fuType = io.in.map(_.bits.ctrl.fuType)
  private val isLs = fuType.map(f => FuType.isLoadStore(f))
  private val isStore = fuType.map(f => FuType.isStore(f))

  private def isBlocked(index: Int): Bool = {
    if (index >= 2) {
      val pairs = (0 until index).flatMap(i => (i + 1 until index).map(j => (i, j)))
      val foundLoad = pairs.map(x => io.in(x._1).valid && io.in(x._2).valid && !isStore(x._1) && !isStore(x._2)).reduce(_|_)
      val foundStore = pairs.map(x => io.in(x._1).valid && io.in(x._2).valid && isStore(x._1) && isStore(x._2)).reduce(_|_)
      val shouldBlockHere = Mux(isStore(index), foundStore, foundLoad) && io.in(index).valid
      //When instructions before are blocked, block this and successors.
      shouldBlockHere || isBlocked(index - 1)
    }
    else {
      false.B
    }
  }

  for (i <- io.in.indices) {
    enqCtrl.needAlloc(i) := Mux(io.in(i).valid && isLs(i), Mux(isStore(i), 2.U, 1.U), 0.U)
    enqCtrl.req(i).valid := io.out(i).fire
    enqCtrl.req(i).bits := io.in(i).bits

    io.out(i).valid := io.in(i).valid && enqCtrl.canAccept
    io.out(i).bits := io.in(i).bits
    io.out(i).bits.lqIdx := enqCtrl.resp(i).lqIdx
    io.out(i).bits.sqIdx := enqCtrl.resp(i).sqIdx

    io.in(i).ready := io.out(i).ready && enqCtrl.canAccept
  }
}
