package TidbitsSimUtils

import Chisel._
import TidbitsAXI._
import TidbitsDMA._
import TidbitsRegFile._
import java.nio.file.{Files, Paths}
import java.nio.ByteBuffer
import java.io.FileOutputStream

// testing infrastructure for wrappable accelerators
// provides "main memory" simulation and a convenient way of setting up the
// control/status registers for setting up the accelerator --
// just like how a CPU would in an SoC-like setting


class WrappableAccelHarness(
  fxn: () => AXIWrappableAccel,
  memWords: Int) extends Module {
  val accel = Module(fxn())
  lazy val p = accel.p
  val rfAddrBits = log2Up(p.numRegs)
  val memAddrBits = log2Up(memWords)
  val memUnitBytes = UInt(p.memDataWidth/8)
  val io = new Bundle {
    // register file access
    val regFileIF = new RegFileSlaveIF(rfAddrBits, p.csrDataWidth)
    // memory access
    val memAddr = UInt(INPUT, p.addrWidth)
    val memWriteEn = Bool(INPUT)
    val memWriteData = UInt(INPUT, p.memDataWidth)
    val memReadData = UInt(OUTPUT, p.memDataWidth)
  }
  val accio = accel.io

  // instantiate regfile
  val regFile = Module(new RegFile(p.numRegs, rfAddrBits, p.csrDataWidth)).io

  // connect regfile to accel ports
  for(i <- 0 until p.numRegs) {
    regFile.regIn(i) <> accel.io.regOut(i)
    accel.io.regIn(i) := regFile.regOut(i)
  }
  // expose regfile interface
  io.regFileIF <> regFile.extIF

  val mem = Mem(UInt(width=p.memDataWidth), memWords)

  // testbench memory access
  def addrToWord(x: UInt) = {x >> UInt(log2Up(p.memDataWidth/8))}
  val memWord = addrToWord(io.memAddr)
  io.memReadData := mem(memWord)

  when (io.memWriteEn) {mem(memWord) := io.memWriteData}

  // accelerator memory access
  // reads
  val sWaitRd :: sRead :: Nil = Enum(UInt(), 2)
  val regStateRead = Reg(init = UInt(sWaitRd))
  val regReadRequest = Reg(init = GenericMemoryRequest(p.toMRP()))

  accio.memRdReq.ready := Bool(false)
  accio.memRdRsp.valid := Bool(false)
  accio.memRdRsp.bits.channelID := regReadRequest.channelID
  accio.memRdRsp.bits.metaData := UInt(0)
  accio.memRdRsp.bits.readData := mem(addrToWord(regReadRequest.addr))

  switch(regStateRead) {
      is(sWaitRd) {
        accio.memRdReq.ready := Bool(true)
        when (accio.memRdReq.valid) {
          regReadRequest := accio.memRdReq.bits
          regStateRead := sRead
        }
      }

      is(sRead) {
        when(regReadRequest.numBytes === UInt(0)) { regStateRead := sWaitRd }
        .otherwise {
          accio.memRdRsp.valid := Bool(true)
          when (accio.memRdRsp.ready) {
            regReadRequest.numBytes := regReadRequest.numBytes - memUnitBytes
            regReadRequest.addr := regReadRequest.addr + UInt(memUnitBytes)
          }
        }
      }
  }

  // writes
  val sWaitWr :: sWrite :: Nil = Enum(UInt(), 2)
  val regStateWrite = Reg(init = UInt(sWaitWr))
  val regWriteRequest = Reg(init = GenericMemoryRequest(p.toMRP()))
  // queue on write response port (to avoid combinational loops)
  val wrRspQ = Module(new Queue(GenericMemoryResponse(p.toMRP()), 16)).io
  wrRspQ.deq <> accio.memWrRsp

  accio.memWrReq.ready := Bool(false)
  accio.memWrDat.ready := Bool(false)
  wrRspQ.enq.valid := Bool(false)
  wrRspQ.enq.bits.driveDefaults()
  wrRspQ.enq.bits.channelID := regWriteRequest.channelID

  switch(regStateWrite) {
      is(sWaitWr) {
        accio.memWrReq.ready := Bool(true)
        when(accio.memWrReq.valid) {
          regWriteRequest := accio.memWrReq.bits
          regStateWrite := sWrite
        }
      }

      is(sWrite) {
        when(regWriteRequest.numBytes === UInt(0)) {regStateWrite := sWaitWr}
        .otherwise {
          when(wrRspQ.enq.ready && accio.memWrDat.valid) {
            accio.memWrDat.ready := Bool(true)
            wrRspQ.enq.valid := Bool(true)
            mem(addrToWord(regWriteRequest.addr)) := accio.memWrDat.bits
            regWriteRequest.numBytes := regWriteRequest.numBytes - memUnitBytes
            regWriteRequest.addr := regWriteRequest.addr + UInt(memUnitBytes)
          }
        }
      }
  }

}

class WrappableAccelTester(c: WrappableAccelHarness) extends Tester(c) {
  // TODO add functions for initializing memory
  val memUnitBytes = c.memUnitBytes.litValue()
  val regFile = c.io.regFileIF
  def nameToRegInd(regName: String): Int = {
    return c.accel.regMap(regName).toInt
  }

  def printAllRegs() = {
    val ks = c.accel.regMap.keys
    var regVals = scala.collection.mutable.Map[String, BigInt]()
    for(k <- ks) {
      regVals(k) = readReg(k)
    }
    for(k <- ks) {
      println(k + " : " + regVals(k).toString)
    }
  }

  def readReg(regName: String): BigInt = {
    val ind = nameToRegInd(regName)
    poke(regFile.cmd.bits.regID, ind)
    poke(regFile.cmd.bits.read, 1)
    poke(regFile.cmd.bits.write, 0)
    poke(regFile.cmd.bits.writeData, 0)
    poke(regFile.cmd.valid, 1)
    step(1)
    poke(regFile.cmd.valid, 0)
    return peek(regFile.readData.bits)
  }

  def expectReg(regName: String, value: BigInt): Boolean = {
    return expect(readReg(regName)==value, regName)
  }

  def writeReg(regName: String, value: BigInt) = {
    val ind = nameToRegInd(regName)
    poke(regFile.cmd.bits.regID, ind)
    poke(regFile.cmd.bits.read, 0)
    poke(regFile.cmd.bits.write, 1)
    poke(regFile.cmd.bits.writeData, value)
    poke(regFile.cmd.valid, 1)
    step(1)
    poke(regFile.cmd.valid, 0)
    step(5) // allow the command to propagate and take effect
  }

  def readMem(addr: BigInt): BigInt = {
    poke(c.io.memAddr, addr)
    return peek(c.io.memReadData)
  }

  def expectMem(addr: BigInt, value: BigInt): Boolean = {
    return expect(readMem(addr) == value, "Mem: "+addr.toString)
  }

  def writeMem(addr: BigInt, value: BigInt) = {
    poke(c.io.memAddr, addr)
    poke(c.io.memWriteEn, 1)
    poke(c.io.memWriteData, value)
    step(1)
    poke(c.io.memWriteEn, 0)
  }

  // read file and write into memory, starting at <baseAddr>
  // use <reorderW> > 0 to reverse byte order (endianness) of every
  // <reorderW>-byte group
  def fileToMem(fileName: String, baseAddr: BigInt, reorderW: Int) = {
    var buf = Files.readAllBytes(Paths.get(fileName))
    if(buf.size % memUnitBytes != 0) {
      println("fileToMem: file size must be multiple of mem unit width")
      System.exit(-1)
    }

    if(reorderW > 0) {
      var reordered = Array[Byte]()
      for(b <- buf.grouped(reorderW)) {
        reordered = reordered ++ b.reverse
      }
      buf = reordered
    }

    var i: Int = 0
    for(b <- buf.grouped(c.p.memDataWidth/8)) {
      val w : BigInt = new BigInt(new java.math.BigInteger(b))
      def valueOf(buf: Array[Byte]): String = buf.map("%02X" format _).mkString
      //println("Read: " + valueOf(w.toByteArray))
      //println("Read: " + valueOf(b))
      writeMem(baseAddr+i*memUnitBytes, w)
      i += 1
    }
  }

  def memToFile(fileName: String, baseAddr: BigInt, wordCount: Int) = {
    val fout = new FileOutputStream(fileName)
    for(i <- 0 until wordCount) {
      var ba = readMem(baseAddr+i*memUnitBytes).toByteArray
      // BigInt.toByteArray returns the min # of bytes needed, pad to
      // cover all bytes read from memory by adding zeroes
      while(ba.size < memUnitBytes) {
        ba = ba ++ Array[Byte](0)
      }
      fout.write(ba)
    }
    fout.close()
  }

  // let the accelerator do internal init (such as writing to the regfile)
  step(10)
  // launch the default test, as defined by the accelerator
  c.accel.defaultTest(this)
}