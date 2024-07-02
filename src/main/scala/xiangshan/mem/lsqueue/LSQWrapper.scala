/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
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

package xiangshan.mem

import org.chipsalliance.cde.config.Parameters
import chisel3._
import chisel3.util._
import utils._
import xiangshan._
import xiangshan.cache._
import xiangshan.backend.rob.RobLsqIO
import xiangshan.vector.HasVectorParameters
import xs.utils.UIntToMask

class ExceptionAddrIO(implicit p: Parameters) extends XSBundle {
  val isStore = Input(Bool())
  val vaddr = Output(UInt(VAddrBits.W))
}

class FwdEntry extends Bundle {
  val validFast = Bool() // validFast is generated the same cycle with query
  val valid = Bool() // valid is generated 1 cycle after query request
  val data = UInt(8.W) // data is generated 1 cycle after query request
}

// inflight miss block reqs
class InflightBlockInfo(implicit p: Parameters) extends XSBundle {
  val block_addr = UInt(PAddrBits.W)
  val valid = Bool()
}

class LsqEnqIO(implicit p: Parameters) extends XSBundle {
  val canAccept = Output(Bool())
  val needAlloc = Vec(exuParameters.LsExuCnt, Input(UInt(2.W)))
  val req = Vec(exuParameters.LsExuCnt, Flipped(ValidIO(new MicroOp)))
  val resp = Vec(exuParameters.LsExuCnt, Output(new LSIdx))
}

class LsqVecDeqIO(implicit p: Parameters) extends XSBundle {
  val loadVectorDeqCnt = UInt(log2Up(LoadQueueSize + 1).W)
  val storeVectorDeqCnt = UInt(log2Up(StoreQueueSize + 1).W)
}

class LQDcacheReqResp(implicit p: Parameters) extends XSBundle {
  val req = DecoupledIO(new DCacheWordReq)
  val resp = Flipped(DecoupledIO(new BankedDCacheWordResp))
}


// Load / Store Queue Wrapper for XiangShan Out of Order LSU
class LsqWrappper(implicit p: Parameters) extends XSModule with HasDCacheParameters with HasPerfEvents {
  val io = IO(new Bundle() {
    val hartId = Input(UInt(8.W))
    val enq = new LsqEnqIO
    val brqRedirect = Flipped(ValidIO(new Redirect))
    val loadPaddrIn = Vec(LoadPipelineWidth, Flipped(Valid(new LqPaddrWriteBundle)))
    val loadIn = Vec(LoadPipelineWidth, Flipped(Valid(new LqWriteBundle)))
    val storeIn = Vec(StorePipelineWidth, Flipped(Valid(new LsPipelineBundle)))
    val storeInRe = Vec(StorePipelineWidth, Input(new LsPipelineBundle()))
    val storeDataIn = Vec(StorePipelineWidth, Flipped(Valid(new ExuOutput))) // store data, send to sq from rs
    val storeMaskIn = Vec(StorePipelineWidth, Flipped(Valid(new StoreMaskBundle))) // store mask, send to sq from rs
    val s2_load_data_forwarded = Vec(LoadPipelineWidth, Input(Bool()))
    val s3_delayed_load_error = Vec(LoadPipelineWidth, Input(Bool()))
    val s2_dcache_require_replay = Vec(LoadPipelineWidth, Input(Bool()))
    val s3_replay_from_fetch = Vec(LoadPipelineWidth, Input(Bool()))
    val sbuffer = Vec(StorePipelineWidth, Decoupled(new DCacheWordReqWithVaddr))
    val ldout = Vec(2, DecoupledIO(new ExuOutput)) // writeback int load
    val ldRawDataOut = Vec(2, Output(new LoadDataFromLQBundle))
    val mmioStout = DecoupledIO(new ExuOutput) // writeback uncached store
    val forward = Vec(LoadPipelineWidth, Flipped(new PipeLoadForwardQueryIO))
    val loadViolationQuery = Vec(LoadPipelineWidth, Flipped(new LoadViolationQueryIO))
    val rob = Flipped(new RobLsqIO)
    val rollback = Output(Valid(new Redirect))
    val dcache = Flipped(ValidIO(new Refill))
    val release = Flipped(ValidIO(new Release))
    val uncache = new UncacheWordIO
    val exceptionAddr = new ExceptionAddrIO
    val sqempty = Output(Bool())
    val issuePtrExt = Output(new SqPtr)
    val sqFull = Output(Bool())
    val lqFull = Output(Bool())
    val lqCancelCnt = Output(UInt(log2Up(LoadQueueSize + 1).W))
    val sqCancelCnt = Output(UInt(log2Up(StoreQueueSize + 1).W))
    val sqDeq = Output(UInt(2.W))
    val trigger = Vec(LoadPipelineWidth, new LqTriggerIO)
    val lqDeq = Output(UInt(log2Up(CommitWidth + 1).W))
    val storeAddrIn = Vec(StorePipelineWidth, Flipped(Decoupled(new ExuOutput)))  // store addr
  })

  val loadQueue = Module(new LoadQueue)
  val storeQueue = Module(new StoreQueue)

  storeQueue.io.hartId := io.hartId

  // io.enq logic
  // LSQ: send out canAccept when both load queue and store queue are ready
  // Dispatch: send instructions to LSQ only when they are ready
  io.enq.canAccept := loadQueue.io.enq.canAccept && storeQueue.io.enq.canAccept
  loadQueue.io.enq.sqCanAccept := storeQueue.io.enq.canAccept
  storeQueue.io.enq.lqCanAccept := loadQueue.io.enq.canAccept

  val loadValid  = Wire(Vec(exuParameters.LsExuCnt,Bool()))
  val storeValid = Wire(Vec(exuParameters.LsExuCnt,Bool()))

  for (i <- io.enq.req.indices) {
    loadQueue.io.enq.needAlloc(i)      := io.enq.needAlloc(i)(0)
    loadQueue.io.enq.req(i).bits       := io.enq.req(i).bits
    loadQueue.io.enq.req(i).bits.sqIdx := storeQueue.io.enq.resp(i)
    loadValid(i)                       := io.enq.needAlloc(i)(0) && io.enq.req(i).valid
    loadQueue.io.enq.req(i).valid      := loadValid(i)

    storeQueue.io.enq.needAlloc(i)      := io.enq.needAlloc(i)(1)
    storeQueue.io.enq.req(i).bits       := io.enq.req(i).bits
    storeQueue.io.enq.req(i).bits       := io.enq.req(i).bits
    storeQueue.io.enq.req(i).bits.lqIdx := loadQueue.io.enq.resp(i)
    storeValid(i)                       := io.enq.needAlloc(i)(1) && io.enq.req(i).valid
    storeQueue.io.enq.req(i).valid      := storeValid(i)

    io.enq.resp(i).lqIdx := loadQueue.io.enq.resp(i)
    io.enq.resp(i).sqIdx := storeQueue.io.enq.resp(i)
  }

  loadQueue.io.enq.reqNum := PopCount(loadValid)
  storeQueue.io.enq.reqNum := PopCount(storeValid)


  // load queue wiring
  loadQueue.io.brqRedirect <> io.brqRedirect
  loadQueue.io.loadPaddrIn <> io.loadPaddrIn
  loadQueue.io.loadIn <> io.loadIn
  loadQueue.io.storeIn.zip(io.storeIn).foreach({case(a, b) =>
    a.valid := b.valid & b.bits.uop.loadStoreEnable
    a.bits := b.bits
  })
  loadQueue.io.s2_load_data_forwarded <> io.s2_load_data_forwarded
  loadQueue.io.s3_delayed_load_error <> io.s3_delayed_load_error
  loadQueue.io.s2_dcache_require_replay <> io.s2_dcache_require_replay
  loadQueue.io.s3_replay_from_fetch <> io.s3_replay_from_fetch
  loadQueue.io.ldout <> io.ldout
  loadQueue.io.ldRawDataOut <> io.ldRawDataOut
  loadQueue.io.robHead := RegNext(io.rob.pendingInst)
  loadQueue.io.lqSafeDeq := RegNext(io.rob.lqSafeDeq)
  loadQueue.io.rollback <> io.rollback
  loadQueue.io.dcache <> io.dcache
  loadQueue.io.release <> io.release
  loadQueue.io.trigger <> io.trigger
  loadQueue.io.exceptionAddr.isStore := DontCare
  loadQueue.io.lqCancelCnt <> io.lqCancelCnt
  io.lqDeq := loadQueue.io.lqDeq

  // store queue wiring
  // storeQueue.io <> DontCare
  storeQueue.io.brqRedirect <> io.brqRedirect
  storeQueue.io.storeIn <> io.storeIn
  storeQueue.io.storeInRe <> io.storeInRe
  storeQueue.io.storeDataIn <> io.storeDataIn
  storeQueue.io.storeAddrIn <> io.storeAddrIn
  storeQueue.io.storeMaskIn <> io.storeMaskIn
  storeQueue.io.sbuffer <> io.sbuffer
  storeQueue.io.mmioStout <> io.mmioStout
  storeQueue.io.rob := RegNext(io.rob.pendingInst)
  storeQueue.io.exceptionAddr.isStore := DontCare
  storeQueue.io.issuePtrExt <> io.issuePtrExt
  storeQueue.io.sqCancelCnt <> io.sqCancelCnt
  storeQueue.io.sqDeq <> io.sqDeq

  loadQueue.io.load_s1 <> io.forward
  storeQueue.io.forward <> io.forward // overlap forwardMask & forwardData, DO NOT CHANGE SEQUENCE

  loadQueue.io.loadViolationQuery <> io.loadViolationQuery

  storeQueue.io.sqempty <> io.sqempty

  // rob commits for lsq is delayed for two cycles, which causes the delayed update for deqPtr in lq/sq
  // s0: commit
  // s1:               exception find
  // s2:               exception triggered
  // s3: ptr updated & new address
  // address will be used at the next cycle after exception is triggered
  io.exceptionAddr.vaddr := Mux(RegNext(io.exceptionAddr.isStore), storeQueue.io.exceptionAddr.vaddr, loadQueue.io.exceptionAddr.vaddr)

  // naive uncache arbiter
  val s_idle :: s_load :: s_store :: Nil = Enum(3)
  val pendingstate = RegInit(s_idle)

  switch(pendingstate){
    is(s_idle){
      when(io.uncache.req.fire){
        pendingstate := Mux(loadQueue.io.uncache.req.valid, s_load, s_store)
      }
    }
    is(s_load){
      when(io.uncache.resp.fire){
        pendingstate := s_idle
      }
    }
    is(s_store){
      when(io.uncache.resp.fire){
        pendingstate := s_idle
      }
    }
  }

  io.uncache.req.valid := loadQueue.io.uncache.req.valid | storeQueue.io.uncache.req.valid
  io.uncache.req.bits := Mux(loadQueue.io.uncache.req.valid, loadQueue.io.uncache.req.bits, storeQueue.io.uncache.req.bits)
  loadQueue.io.uncache.req.ready := loadQueue.io.uncache.req.valid && io.uncache.req.ready
  storeQueue.io.uncache.req.ready := !loadQueue.io.uncache.req.valid && io.uncache.req.ready

  loadQueue.io.uncache.resp.valid := pendingstate === s_load && io.uncache.resp.valid
  loadQueue.io.uncache.resp.bits := io.uncache.resp.bits
  storeQueue.io.uncache.resp.valid := !(pendingstate === s_load) && io.uncache.resp.valid
  storeQueue.io.uncache.resp.bits := io.uncache.resp.bits
  io.uncache.resp.ready := Mux(pendingstate === s_load, loadQueue.io.uncache.resp.ready, storeQueue.io.uncache.resp.ready)

  assert(!(loadQueue.io.uncache.req.valid && storeQueue.io.uncache.req.valid))
  assert(!(loadQueue.io.uncache.resp.valid && storeQueue.io.uncache.resp.valid))
  assert(!((loadQueue.io.uncache.resp.valid || storeQueue.io.uncache.resp.valid) && pendingstate === s_idle))

  io.lqFull := loadQueue.io.lqFull
  io.sqFull := storeQueue.io.sqFull

  val perfEvents = Seq(loadQueue, storeQueue).flatMap(_.getPerfEvents)
  generatePerfEvent()
}

class LsqEnqCtrl(implicit p: Parameters) extends XSModule with HasVectorParameters{
  val io = IO(new Bundle {
    val redirect = Flipped(ValidIO(new Redirect))
    // to dispatch
    val enq = new LsqEnqIO
    // from rob
    val lcommit = Input(UInt(log2Up(CommitWidth + 1).W))
    val scommit = Input(UInt(log2Up(CommitWidth + 1).W))
    //from lsq
    val lsqVecDeqIO = Input(new LsqVecDeqIO)
    // from/tp lsq
    val lqCancelCnt = Input(UInt(log2Up(LoadQueueSize + MemVectorInstructionMax + 1).W))
    val sqCancelCnt = Input(UInt(log2Up(StoreQueueSize + MemVectorInstructionMax + 1).W))
    val enqLsq = Flipped(new LsqEnqIO)
  })

  val lqVecDeqNum = io.lsqVecDeqIO.loadVectorDeqCnt
  val sqVecDeqNum = io.lsqVecDeqIO.storeVectorDeqCnt
  val lqPtr = RegInit(0.U.asTypeOf(new LqPtr))
  val sqPtr = RegInit(0.U.asTypeOf(new SqPtr))
  val lqCounter = RegInit(LoadQueueSize.U(log2Up(LoadQueueSize + 1).W))
  val sqCounter = RegInit(StoreQueueSize.U(log2Up(StoreQueueSize + 1).W))
  val canAccept = RegInit(false.B)

  val loadEnqNumber = PopCount(io.enq.req.zip(io.enq.needAlloc).map(x => x._1.valid && x._2(0)))
  val storeEnqNumber = PopCount(io.enq.req.zip(io.enq.needAlloc).map(x => x._1.valid && x._2(1)))

  // How to update ptr and counter:
  // (1) by default, updated according to enq/commit
  // (2) when redirect and dispatch queue is empty, update according to lsq
  val t1_redirect = RegNext(io.redirect.valid)
  val t2_redirect = RegNext(t1_redirect)
  val t2_update = t2_redirect && !VecInit(io.enq.needAlloc.map(_.orR)).asUInt.orR
  val t3_update = RegNext(t2_update)
  val t3_lqCancelCnt = RegNext(io.lqCancelCnt)
  val t3_sqCancelCnt = RegNext(io.sqCancelCnt)
  when (t3_update) {
    lqPtr := lqPtr - t3_lqCancelCnt
    lqCounter := lqCounter + io.lcommit + t3_lqCancelCnt + lqVecDeqNum
    sqPtr := sqPtr - t3_sqCancelCnt
    sqCounter := sqCounter + io.scommit + t3_sqCancelCnt + sqVecDeqNum
  }.elsewhen (!io.redirect.valid && io.enq.canAccept) {
    lqPtr := lqPtr + loadEnqNumber
    lqCounter := lqCounter + io.lcommit - loadEnqNumber + lqVecDeqNum
    sqPtr := sqPtr + storeEnqNumber
    sqCounter := sqCounter + io.scommit - storeEnqNumber + sqVecDeqNum
  }.otherwise {
    lqCounter := lqCounter + io.lcommit + lqVecDeqNum
    sqCounter := sqCounter + io.scommit + sqVecDeqNum
  }


//  val maxAllocate = Seq(exuParameters.LduCnt, exuParameters.StuCnt).max
  val maxAllocate = exuParameters.LduCnt + exuParameters.StuCnt
  val ldCanAccept = lqCounter >= loadEnqNumber +& maxAllocate.U
  val sqCanAccept = sqCounter >= storeEnqNumber +& maxAllocate.U
  // It is possible that t3_update and enq are true at the same clock cycle.
  // For example, if redirect.valid lasts more than one clock cycle,
  // after the last redirect, new instructions may enter but previously redirect
  // has not been resolved (updated according to the cancel count from LSQ).
  // To solve the issue easily, we block enqueue when t3_update, which is RegNext(t2_update).
  io.enq.canAccept := RegNext(ldCanAccept && sqCanAccept && !t2_update)
  val lqOffset = Wire(Vec(io.enq.resp.length, UInt(log2Up(maxAllocate + 1).W)))
  val sqOffset = Wire(Vec(io.enq.resp.length, UInt(log2Up(maxAllocate + 1).W)))
  for ((resp, i) <- io.enq.resp.zipWithIndex) {
    lqOffset(i) := PopCount(io.enq.needAlloc.take(i).map(a => a(0)))
    resp.lqIdx := lqPtr + lqOffset(i)
    sqOffset(i) := PopCount(io.enq.needAlloc.take(i).map(a => a(1)))
    resp.sqIdx := sqPtr + sqOffset(i)
  }

  io.enqLsq.needAlloc := RegNext(io.enq.needAlloc)
  io.enqLsq.req.zip(io.enq.req).zip(io.enq.resp).foreach{ case ((toLsq, enq), resp) =>
    val do_enq = enq.valid && !io.redirect.valid && io.enq.canAccept
    toLsq.valid := RegNext(do_enq)
    toLsq.bits := RegEnable(enq.bits, do_enq)
    toLsq.bits.lqIdx := RegEnable(resp.lqIdx, do_enq)
    toLsq.bits.sqIdx := RegEnable(resp.sqIdx, do_enq)
  }

}
