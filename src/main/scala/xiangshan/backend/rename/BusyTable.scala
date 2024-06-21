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

package xiangshan.backend.rename

import org.chipsalliance.cde.config.Parameters
import chisel3._
import chisel3.util._
import xiangshan._
import utils._
import xs.utils.ParallelOR
import xs.utils.perf.HasPerfLogging

class BusyTableReadIO(implicit p: Parameters) extends XSBundle {
  val req = Input(UInt(PhyRegIdxWidth.W))
  val resp = Output(Bool())
}

class BusyTable(size:Int, numReadPorts: Int, numWritePorts: Int, renameWidth:Int, needAllocBypass:Boolean = false)(implicit p: Parameters) extends XSModule
  with HasPerfEvents with HasPerfLogging{
  val io = IO(new Bundle() {
    // set preg state to busy
    val allocPregs = Vec(renameWidth, Flipped(ValidIO(UInt(PhyRegIdxWidth.W))))
    // set preg state to ready (write back regfile + rob walk)
    val wbPregs = Vec(numWritePorts, Flipped(ValidIO(UInt(PhyRegIdxWidth.W))))
    // read preg state
    val read = Vec(numReadPorts, new BusyTableReadIO)
  })

  val table = RegInit(0.U(size.W))

  def reqVecToMask(rVec: Vec[Valid[UInt]]): UInt = {
    ParallelOR(rVec.map(v => Mux(v.valid, UIntToOH(v.bits), 0.U)))
  }

  val wbMask = reqVecToMask(io.wbPregs)
  val allocMask = reqVecToMask(io.allocPregs)

  val tableAfterWb = table & (~wbMask).asUInt
  val tableAfterAlloc = tableAfterWb | allocMask

  //Only bypass wb data to read
  private def read(addr:UInt, table:UInt):Bool = {
    val addrSel = Seq.tabulate(size)(_.U === addr)
    !Mux1H(addrSel, table.asBools)
  }
  io.read.foreach(r => {
    if(needAllocBypass){
      r.resp := read(r.req, tableAfterWb | tableAfterAlloc)
    }else{
      r.resp := read(r.req, tableAfterWb)
    }
  })
  table := tableAfterAlloc

  val oddTable = table.asBools.zipWithIndex.filter(_._2 % 2 == 1).map(_._1)
  val evenTable = table.asBools.zipWithIndex.filter(_._2 % 2 == 0).map(_._1)
  val busyCount = RegNext(RegNext(PopCount(oddTable)) + RegNext(PopCount(evenTable)))

  XSDebug(p"table    : ${Binary(table)}\n")
  XSDebug(p"tableNext: ${Binary(tableAfterAlloc)}\n")
  XSDebug(p"allocMask: ${Binary(allocMask)}\n")
  XSDebug(p"wbMask   : ${Binary(wbMask)}\n")
  for (i <- 0 until size) {
    XSDebug(table(i), "%d is busy\n", i.U)
  }

  XSPerfAccumulate("busy_count", PopCount(table))

  val perfEvents = Seq(
    ("std_freelist_1_4_valid", busyCount < (size / 4).U                                      ),
    ("std_freelist_2_4_valid", busyCount > (size / 4).U && busyCount <= (size / 2).U    ),
    ("std_freelist_3_4_valid", busyCount > (size / 2).U && busyCount <= (size * 3 / 4).U),
    ("std_freelist_4_4_valid", busyCount > (size * 3 / 4).U                                  )
  )
  generatePerfEvent()
}
