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

package top

import org.chipsalliance.cde.config.Parameters
import chisel3.stage.ChiselGeneratorAnnotation
import chisel3._
import device.{AXI4MemorySlave, SimJTAG}
import freechips.rocketchip.diplomacy.{DisableMonitors, LazyModule}
import xs.utils.{FileRegisters, GTimer}
import difftest._
import circt.stage.FirtoolOption
import xs.utils.perf.DebugOptionsKey

class SimTop(implicit p: Parameters) extends Module {
  val debugOpts = p(DebugOptionsKey)

  val l_soc = LazyModule(new XSTop())
  val soc = Module(l_soc.module)

//  l_soc.module.dma <> 0.U.asTypeOf(l_soc.module.dma)

  val l_simMMIO = LazyModule(new SimMMIO(l_soc.misc.peripheralNode.in.head._2, l_soc.misc.l3FrontendAXI4Node.out.head._2))
  val simMMIO = Module(l_simMMIO.module)
  l_simMMIO.io_axi4 <> soc.peripheral

  val l_simAXIMem = AXI4MemorySlave(
    l_soc.misc.memAXI4SlaveNode,
    16L * 1024 * 1024 * 1024,
    useBlackBox = true,
    dynamicLatency = debugOpts.UseDRAMSim
  )
  val simAXIMem = Module(l_simAXIMem.module)
  l_simAXIMem.io_axi4 <> soc.memory
  soc.dma <> l_simMMIO.io_dma

  val freq = 100
  val cnt = RegInit((freq - 1).U)
  val tick = cnt < (freq / 2).U
  cnt := Mux(cnt === 0.U, (freq - 1).U, cnt - 1.U)

  soc.rtc_clock := tick
  soc.io.clock := clock
  soc.io.reset := (reset.asBool || soc.io.debug_reset).asAsyncReset
  soc.io.extIntrs := simMMIO.io.interrupt.intrVec
  soc.scan_mode := false.B
  soc.dft_lgc_rst_n := true.B.asAsyncReset
  soc.dft_mode := false.B
  soc.io.riscv_rst_vec.foreach(_ := 0x10000000L.U)
  soc.bootrom_disable := true.B
  if(soc.dft.isDefined) {
    soc.dft.get.cgen := false.B
    soc.dft.get.ram_aux_clk := false.B
    soc.dft.get.ram_aux_ckbp := false.B
    soc.dft.get.ram_hold := false.B
    soc.dft.get.ram_bypass := false.B
    soc.dft.get.ram_bp_clken := false.B
    soc.dft.get.ram_mcp_hold := false.B
  }

  val success = Wire(Bool())
  val jtag = Module(new SimJTAG(tickDelay=3)(p))
  jtag.connect(soc.io.systemjtag.jtag, clock, reset.asBool, !reset.asBool, success)
  soc.io.systemjtag.reset := reset.asAsyncReset
  soc.io.systemjtag.mfr_id := 0.U(11.W)
  soc.io.systemjtag.part_number := 0.U(16.W)
  soc.io.systemjtag.version := 0.U(4.W)

  val io = IO(new Bundle(){
    val logCtrl = new LogCtrlIO
    val perfInfo = new PerfInfoIO
    val uart = new UARTIO
    val simFinal = Input(Bool())
  })

  simMMIO.io.uart <> io.uart

  if (!debugOpts.FPGAPlatform && debugOpts.EnablePerfDebug) {
    val timer = Wire(UInt(64.W))
    val logEnable = Wire(Bool())
    val clean = Wire(Bool())
    val dump = Wire(Bool())
    timer := GTimer()
    logEnable := (timer >= io.logCtrl.log_begin) && (timer < io.logCtrl.log_end)
    clean := RegNext(io.perfInfo.clean, false.B)
    dump := io.perfInfo.dump
    dontTouch(timer)
    dontTouch(logEnable)
    dontTouch(clean)
    dontTouch(dump)
  }

  DifftestModule.finish("XiangShan")
}

object SimTop extends App {
  // Keep this the same as TopMain except that SimTop is used here instead of XSTop
  val (config, firrtlOpts) = ArgParser.parse(args)
  xs.utils.GlobalData.prefix = config(PrefixKey)
  difftest.GlobalData.prefix = config(PrefixKey)
  (new XiangShanStage).execute(firrtlOpts, Seq(
    FirtoolOption("-O=release"),
    FirtoolOption("--disable-all-randomization"),
    FirtoolOption("--disable-annotation-unknown"),
    FirtoolOption("--strip-debug-info"),
    FirtoolOption("--lower-memories"),
    FirtoolOption("--add-vivado-ram-address-conflict-synthesis-bug-workaround"),
    FirtoolOption("--lowering-options=noAlwaysComb," +
      " disallowPortDeclSharing, disallowLocalVariables," +
      " emittedLineLength=120, explicitBitcast, locationInfoStyle=plain," +
      " disallowExpressionInliningInPorts, disallowMuxInlining"),
    ChiselGeneratorAnnotation(() => {
      DisableMonitors(p => new SimTop()(p))(config)
    })
  ))
  FileRegisters.write(filePrefix = config(PrefixKey) + "XSTop.")
}
