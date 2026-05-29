package accelerator

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class TopSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Top"

  it should "instantiate correctly" in {
    test(new Top(2, 2, 8, 64)) { dut =>
      dut.io.busy.expect(false.B)
      dut.io.done.expect(false.B)
    }
  }

  it should "perform vector x matrix multiplication (2D)" in {
    test(new Top(2, 2, 8, 64)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      println("\n=== Vector × Matrix Multiplication Test ===")
      println("Computing: v × W where")
      println("  v = [3, 5] (1×2 vector)")
      println("  W = [[2, 4],")
      println("       [1, 3]] (2×2 matrix)")
      println()
      println("Expected result: v × W = [3*2+5*1, 3*4+5*3] = [11, 27]")
      println()
      println("Systolic array dataflow:")
      println("  - Row 0 receives activation = 3")
      println("  - Row 1 receives activation = 5")
      println("  - Column 0 accumulates: 3*2 + 5*1 = 11")
      println("  - Column 1 accumulates: 3*4 + 5*3 = 27")
      println()
      
      // Define test data
      val weights = Seq(
        Seq(2, 4),  // Row 0: PE[0][0]=2, PE[0][1]=4
        Seq(1, 3)   // Row 1: PE[1][0]=1, PE[1][1]=3
      )
      val activations = Seq(3, 5)  // v[0]=3 for row 0, v[1]=5 for row 1
      val expectedResults = Seq(11, 27)  // Column 0 result = 11, Column 1 result = 27
      
      // Step 1: Load weights into buffer
      println("--- Step 1: Loading weights into buffer ---")
      for (row <- 0 until 2) {
        for (col <- 0 until 2) {
          val addr = row * 2 + col
          val weight = weights(row)(col)
          println(s"  Weight[$row][$col] = $weight → buffer addr $addr")
          dut.io.weightWriteEnable.poke(true.B)
          dut.io.weightWriteAddr.poke(addr.U)
          dut.io.weightWriteData.poke(weight.U)
          dut.clock.step()
        }
      }
      dut.io.weightWriteEnable.poke(false.B)
      dut.clock.step()
      
      // Step 2: Load activations into buffer
      println("\n--- Step 2: Loading activations into buffer ---")
      for (i <- 0 until 2) {
        println(s"  Activation[$i] = ${activations(i)} → buffer addr $i")
        dut.io.activationWriteEnable.poke(true.B)
        dut.io.activationWriteAddr.poke(i.U)
        dut.io.activationWriteData.poke(activations(i).U)
        dut.clock.step()
      }
      dut.io.activationWriteEnable.poke(false.B)
      dut.clock.step(2)
      
      // Step 3: Start computation
      println("\n--- Step 3: Starting computation ---")
      println("  Controller: IDLE → LOAD_WEIGHTS → COMPUTE → DONE")
      dut.io.start.poke(true.B)
      dut.io.loadWeights.poke(true.B)
      // dut.io.computeCycles.poke(4.U)  // Need enough cycles for data to flow through
      dut.clock.step()
      dut.io.start.poke(false.B)
      
      // Step 4: Wait for completion
      var cycleCount = 0
      while (!dut.io.done.peek().litToBoolean && cycleCount < 20) {
        dut.clock.step()
        cycleCount += 1
      }
      println(s"\n--- Step 4: Computation completed in $cycleCount cycles ---")
      dut.io.done.expect(true.B)
      
      // Step 5: Read and verify result
      println("\n--- Step 5: Reading results ---")
      dut.clock.step(2)
      
      dut.io.resultReadEnable.poke(true.B)
      dut.io.resultReadAddr.poke(0.U)
      dut.clock.step()
      
      val result = dut.io.resultReadData.peek().litValue
      println(s"  Result from column 0 = $result")
      println(s"  Expected (3*2 + 5*1) = 11")
      
      dut.io.resultReadEnable.poke(true.B)
      dut.io.resultReadAddr.poke(1.U)
      dut.clock.step()
      
      val result1 = dut.io.resultReadData.peek().litValue
      println(s"  Result from column 1 = $result1")
      println(s"  Expected (3*4 + 5*3) = 27")
      
      // Verify result is correct
      println("\n--- Verification ---")
      if (result == 11) {
        println("  ✓ PASS: Result matches expected value!")
      } else {
        println(s"  ✗ FAIL: Expected 11, got $result")
        println("\n  Possible issues:")
        println("    - Activations not distributed correctly to each row")
        println("    - Partial sums not accumulating down columns properly")
        println("    - Timing issue in data flow")
      }
      
      assert(result == 11, s"Vector×Matrix result should be 11, got $result")
      assert(result1 == 27, s"Vector×Matrix column 1 result should be 27, got $result1")
    }
  }

  it should "perform 2x2 matrix multiplication" in {
    test(new Top(2, 2, 8, 64)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      println("\n=== Matrix × Matrix Multiplication Test ===")
      println("Computing: A × B where")
      println("  A = [[2, 3],")
      println("       [4, 5]] (2×2 matrix)")
      println("  B = [[1, 2],")
      println("       [3, 4]] (2×2 matrix)")
      println()
      println("Expected result C = A × B:")
      println("  C[0,0] = 2*1 + 3*3 = 11    C[0,1] = 2*2 + 3*4 = 16")
      println("  C[1,0] = 4*1 + 5*3 = 19    C[1,1] = 4*2 + 5*4 = 28")
      println()
      println("Implementation: Two-pass approach")
      println("  Pass 1: Process row [2, 3] → results [11, 16]")
      println("  Pass 2: Process row [4, 5] → results [19, 28]")
      println()
      
      // Define test data
      val matrixA = Seq(
        Seq(2, 3),  // Row 0
        Seq(4, 5)   // Row 1
      )
      val matrixB = Seq(
        Seq(1, 2),  // Row 0 (PE weights)
        Seq(3, 4)   // Row 1 (PE weights)
      )
      val expectedC = Seq(
        Seq(11, 16),  // Result row 0
        Seq(19, 28)   // Result row 1
      )
      
      // Load weights (stationary - same for both passes)
      println("--- Loading weight matrix B into PE array ---")
      for (row <- 0 until 2) {
        for (col <- 0 until 2) {
          val addr = row * 2 + col
          val weight = matrixB(row)(col)
          println(s"  B[$row][$col] = $weight → PE[$row][$col]")
          dut.io.weightWriteEnable.poke(true.B)
          dut.io.weightWriteAddr.poke(addr.U)
          dut.io.weightWriteData.poke(weight.U)
          dut.clock.step()
        }
      }
      dut.io.weightWriteEnable.poke(false.B)
      dut.clock.step()
      
      // ============= PASS 1: Process first row of A =============
      println("\n" + "="*60)
      println("PASS 1: Computing C[0] = A[0] × B = [2, 3] × B")
      println("="*60)
      
      val row0 = matrixA(0)
      println(s"Loading activations: [${row0.mkString(", ")}]")
      for (i <- 0 until 2) {
        dut.io.activationWriteEnable.poke(true.B)
        dut.io.activationWriteAddr.poke(i.U)
        dut.io.activationWriteData.poke(row0(i).U)
        dut.clock.step()
      }
      dut.io.activationWriteEnable.poke(false.B)
      dut.clock.step(2)
      
      println("Starting computation (load weights + compute)...")
      dut.io.start.poke(true.B)
      dut.io.loadWeights.poke(true.B)
      // dut.io.computeCycles.poke(3.U)
      dut.clock.step()
      dut.io.start.poke(false.B)
      
      var cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 20) {
        dut.clock.step()
        cycles += 1
      }
      println(s"Pass 1 completed in $cycles cycles")
      dut.io.done.expect(true.B)
      
      // Read result from Pass 1
      dut.clock.step(2)
      dut.io.resultReadEnable.poke(true.B)
      dut.io.resultReadAddr.poke(0.U)
      dut.clock.step()
      val result1col0 = dut.io.resultReadData.peek().litValue

      dut.io.resultReadAddr.poke(1.U)
      dut.clock.step()
      val result1col1 = dut.io.resultReadData.peek().litValue

      println(s"\nPass 1 Result (column 0): $result1col0, expected ${expectedC(0)(0)}")
      println(s"Pass 1 Result (column 1): $result1col1, expected ${expectedC(0)(1)}")

      // Small delay between passes
      dut.io.resultReadEnable.poke(false.B)
      dut.clock.step(5)
      
      // ============= PASS 2: Process second row of A =============
      println("\n" + "="*60)
      println("PASS 2: Computing C[1] = A[1] × B = [4, 5] × B")
      println("="*60)
      
      val row1 = matrixA(1)
      println(s"Loading activations: [${row1.mkString(", ")}]")
      for (i <- 0 until 2) {
        dut.io.activationWriteEnable.poke(true.B)
        dut.io.activationWriteAddr.poke(i.U)
        dut.io.activationWriteData.poke(row1(i).U)
        dut.clock.step()
      }
      dut.io.activationWriteEnable.poke(false.B)
      dut.clock.step(2)
      
      println("Starting computation (weights already loaded)...")
      dut.io.start.poke(true.B)
      dut.io.loadWeights.poke(false.B)  // Weights already in PEs
      // dut.io.computeCycles.poke(3.U)
      dut.clock.step()
      dut.io.start.poke(false.B)
      
      cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 20) {
        dut.clock.step()
        cycles += 1
      }
      println(s"Pass 2 completed in $cycles cycles")
      dut.io.done.expect(true.B)
      
      // Read result from Pass 2
      dut.clock.step(2)
      dut.io.resultReadEnable.poke(true.B)
      dut.io.resultReadAddr.poke(0.U)
      dut.clock.step()
      val result2col0 = dut.io.resultReadData.peek().litValue

      dut.io.resultReadAddr.poke(1.U)
      dut.clock.step()
      val result2col1 = dut.io.resultReadData.peek().litValue

      println(s"\nPass 2 Result (column 0): $result2col0, expected ${expectedC(1)(0)}")
      println(s"Pass 2 Result (column 1): $result2col1, expected ${expectedC(1)(1)}")

      // Final summary
      println("\n" + "="*60)
      println("MATRIX MULTIPLICATION SUMMARY")
      println("="*60)
      println(s"Pass 1: [2,3] × B → col0=$result1col0 (exp ${expectedC(0)(0)}), col1=$result1col1 (exp ${expectedC(0)(1)})")
      println(s"Pass 2: [4,5] × B → col0=$result2col0 (exp ${expectedC(1)(0)}), col1=$result2col1 (exp ${expectedC(1)(1)})")
      println()

      // Assertions
      assert(result1col0 == expectedC(0)(0),
        s"Pass 1 col 0 should be ${expectedC(0)(0)}, got $result1col0")
      assert(result1col1 == expectedC(0)(1),
        s"Pass 1 col 1 should be ${expectedC(0)(1)}, got $result1col1")
      assert(result2col0 == expectedC(1)(0),
        s"Pass 2 col 0 should be ${expectedC(1)(0)}, got $result2col0")
      assert(result2col1 == expectedC(1)(1),
        s"Pass 2 col 1 should be ${expectedC(1)(1)}, got $result2col1")
      
      println("✓ All matrix multiplication tests passed!")
    }
  }

  it should "perform simple MAC operation" in {
    test(new Top(2, 2, 8, 64)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      println("\n=== Simple MAC Test ===")
      println("Single PE: weight=3, activation=4, expected=12")
      
      // Load a single weight
      dut.io.weightWriteEnable.poke(true.B)
      dut.io.weightWriteAddr.poke(0.U)
      dut.io.weightWriteData.poke(3.U)
      dut.clock.step()
      dut.io.weightWriteEnable.poke(false.B)
      
      // Load a single activation
      dut.io.activationWriteEnable.poke(true.B)
      dut.io.activationWriteAddr.poke(0.U)
      dut.io.activationWriteData.poke(4.U)
      dut.clock.step()
      dut.io.activationWriteEnable.poke(false.B)
      dut.clock.step(2)
      
      // Run computation
      dut.io.start.poke(true.B)
      dut.io.loadWeights.poke(true.B)
      // dut.io.computeCycles.poke(1.U)
      dut.clock.step()
      dut.io.start.poke(false.B)
      
      // Wait for done
      var cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 20) {
        dut.clock.step()
        cycles += 1
      }
      
      dut.io.done.expect(true.B)
      println(s"Computation done after $cycles cycles")
      
      // Read result
      dut.clock.step(2)
      dut.io.resultReadEnable.poke(true.B)
      dut.io.resultReadAddr.poke(0.U)
      dut.clock.step()
      
      val result = dut.io.resultReadData.peek().litValue
      println(s"Result = $result (expected 12)")

      assert(result == 12, s"Result $result should be exactly 12 (weight=3, activation=4)")
    }
  }

  it should "complete a computation cycle" in {
    test(new Top(2, 2, 8, 64)) { dut =>
      // Start computation
      dut.io.start.poke(true.B)
      dut.io.loadWeights.poke(false.B)
      // dut.io.computeCycles.poke(3.U)
      dut.clock.step()
      
      // Wait for completion
      dut.clock.step(7)
      dut.io.done.expect(true.B)
    }
  }

  it should "perform 4x4 vector x matrix multiplication" in {
    test(new Top(4, 4, 8, 256)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      println("\n=== 4×4 Vector × Matrix Multiplication Test ===")
      println("Computing: v × W where")
      println("  v = [1, 2, 3, 4] (1×4 vector)")
      println("  W = [[1, 2, 3, 4],")
      println("       [5, 6, 7, 8],")
      println("       [1, 1, 1, 1],")
      println("       [2, 2, 2, 2]] (4×4 matrix)")
      println()
      println("Expected result: v × W")
      println("  Column 0: 1*1 + 2*5 + 3*1 + 4*2 = 1+10+3+8 = 22")
      println("  Column 1: 1*2 + 2*6 + 3*1 + 4*2 = 2+12+3+8 = 25")
      println("  Column 2: 1*3 + 2*7 + 3*1 + 4*2 = 3+14+3+8 = 28")
      println("  Column 3: 1*4 + 2*8 + 3*1 + 4*2 = 4+16+3+8 = 31")
      println("Expected: [22, 25, 28, 31]")
      println()
      
      // Define test data
      val weights = Seq(
        Seq(1, 2, 3, 4),
        Seq(5, 6, 7, 8),
        Seq(1, 1, 1, 1),
        Seq(2, 2, 2, 2)
      )
      val activations = Seq(1, 2, 3, 4)
      val expectedResults = Seq(22, 25, 28, 31)
      
      // Load weights into buffer
      println("--- Loading weights into buffer ---")
      for (row <- 0 until 4) {
        for (col <- 0 until 4) {
          val addr = row * 4 + col
          val weight = weights(row)(col)
          dut.io.weightWriteEnable.poke(true.B)
          dut.io.weightWriteAddr.poke(addr.U)
          dut.io.weightWriteData.poke(weight.U)
          dut.clock.step()
        }
      }
      dut.io.weightWriteEnable.poke(false.B)
      dut.clock.step()
      
      // Load activations into buffer
      println("--- Loading activations into buffer ---")
      for (i <- 0 until 4) {
        dut.io.activationWriteEnable.poke(true.B)
        dut.io.activationWriteAddr.poke(i.U)
        dut.io.activationWriteData.poke(activations(i).U)
        dut.clock.step()
      }
      dut.io.activationWriteEnable.poke(false.B)
      dut.clock.step(2)
      
      // Start computation
      println("--- Starting computation ---")
      dut.io.start.poke(true.B)
      dut.io.loadWeights.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)
      
      // Wait for done
      var cycleCount = 0
      while (!dut.io.done.peek().litToBoolean && cycleCount < 30) {
        dut.clock.step()
        cycleCount += 1
      }
      println(s"Computation completed in $cycleCount cycles")
      dut.io.done.expect(true.B)
      
      // Read and verify results
      println("\n--- Reading and verifying results ---")
      dut.clock.step(2)
      
      var allPassed = true
      for (c <- 0 until 4) {
        dut.io.resultReadEnable.poke(true.B)
        dut.io.resultReadAddr.poke(c.U)
        dut.clock.step()
        
        val result = dut.io.resultReadData.peek().litValue
        val expected = expectedResults(c)
        println(s"  Column $c: result=$result, expected=$expected ${if (result == expected) "✓" else "✗"}")
        
        if (result != expected) allPassed = false
      }
      dut.io.resultReadEnable.poke(false.B)
      
      println()
      if (allPassed) {
        println("✓ All results match expected values!")
      } else {
        println("✗ Some results do not match")
      }
      
      assert(allPassed, "4×4 vector×matrix results should match expected values")
    }
  }

  it should "perform 4x4 matrix x matrix multiplication" in {
    test(new Top(4, 4, 8, 256)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      println("\n=== 4×4 Matrix × Matrix Multiplication Test ===")
      println("Computing: A × B where")
      println("  A = [[1, 2, 1, 0],")
      println("       [3, 1, 0, 1],")
      println("       [0, 2, 1, 3],")
      println("       [1, 0, 2, 1]] (fully non-zero 4×4 matrix)")
      println()
      println("  B = [[2, 3, 4, 5],")
      println("       [1, 2, 3, 4],")
      println("       [5, 5, 5, 5],")
      println("       [1, 1, 1, 1]] (4×4 matrix)")
      println()
      println("Expected result C = A × B:")
      println("  Row 0: [1*2+2*1+1*5+0*1, 1*3+2*2+1*5+0*1, 1*4+2*3+1*5+0*1, 1*5+2*4+1*5+0*1] = [9, 12, 15, 18]")
      println("  Row 1: [3*2+1*1+0*5+1*1, 3*3+1*2+0*5+1*1, 3*4+1*3+0*5+1*1, 3*5+1*4+0*5+1*1] = [8, 12, 16, 20]")
      println("  Row 2: [0*2+2*1+1*5+3*1, 0*3+2*2+1*5+3*1, 0*4+2*3+1*5+3*1, 0*5+2*4+1*5+3*1] = [10, 12, 14, 16]")
      println("  Row 3: [1*2+0*1+2*5+1*1, 1*3+0*2+2*5+1*1, 1*4+0*3+2*5+1*1, 1*5+0*4+2*5+1*1] = [13, 14, 15, 16]")
      println()

      // Define test matrices
      val matrixA = Seq(
        Seq(1, 2, 1, 0),
        Seq(3, 1, 0, 1),
        Seq(0, 2, 1, 3),
        Seq(1, 0, 2, 1)
      )

      val matrixB = Seq(
        Seq(2, 3, 4, 5),
        Seq(1, 2, 3, 4),
        Seq(5, 5, 5, 5),
        Seq(1, 1, 1, 1)
      )

      val expectedResults = Seq(
        Seq(9,  12, 15, 18),
        Seq(8,  12, 16, 20),
        Seq(10, 12, 14, 16),
        Seq(13, 14, 15, 16)
      )
      
      // Load weight matrix B
      println("--- Loading weight matrix B into PE array ---")
      for (row <- 0 until 4) {
        for (col <- 0 until 4) {
          val addr = row * 4 + col
          val weight = matrixB(row)(col)
          dut.io.weightWriteEnable.poke(true.B)
          dut.io.weightWriteAddr.poke(addr.U)
          dut.io.weightWriteData.poke(weight.U)
          dut.clock.step()
        }
      }
      dut.io.weightWriteEnable.poke(false.B)
      dut.clock.step()
      
      // Process each row of matrix A
      var allPassed = true
      for (rowIdx <- 0 until 4) {
        println(s"\n============================================================")
        println(s"PASS ${rowIdx + 1}: Computing C[$rowIdx] = A[$rowIdx] × B")
        println(s"============================================================")
        
        val currentRow = matrixA(rowIdx)
        println(s"Loading activations: [${currentRow.mkString(", ")}]")
        
        // Load activations for this row
        for (i <- 0 until 4) {
          dut.io.activationWriteEnable.poke(true.B)
          dut.io.activationWriteAddr.poke(i.U)
          dut.io.activationWriteData.poke(currentRow(i).U)
          dut.clock.step()
        }
        dut.io.activationWriteEnable.poke(false.B)
        dut.clock.step(2)
        
        // Start computation
        println("Starting computation...")
        dut.io.start.poke(true.B)
        dut.io.loadWeights.poke(if (rowIdx == 0) true.B else false.B)
        dut.clock.step()
        dut.io.start.poke(false.B)
        
        // Wait for completion
        var cycles = 0
        while (!dut.io.done.peek().litToBoolean && cycles < 30) {
          dut.clock.step()
          cycles += 1
        }
        println(s"Pass ${rowIdx + 1} completed in $cycles cycles")
        dut.io.done.expect(true.B)
        
        // Read and verify results for this row
        dut.clock.step(2)
        println(s"\nRow $rowIdx results:")
        for (c <- 0 until 4) {
          dut.io.resultReadEnable.poke(true.B)
          dut.io.resultReadAddr.poke(c.U)
          dut.clock.step()
          
          val result = dut.io.resultReadData.peek().litValue
          val expected = expectedResults(rowIdx)(c)
          println(s"  Column $c: result=$result, expected=$expected ${if (result == expected) "✓" else "✗"}")
          
          if (result != expected) allPassed = false
        }
        dut.io.resultReadEnable.poke(false.B)
        
        // Small delay before next pass
        dut.clock.step(3)
      }
      
      println("\n============================================================")
      println("MATRIX MULTIPLICATION SUMMARY")
      println("============================================================")
      if (allPassed) {
        println("✓ All 4×4 matrix multiplication results correct!")
      } else {
        println("✗ Some results do not match expected values")
      }
      
      assert(allPassed, "4×4 matrix×matrix results should match expected values")
    }
  }

  it should "not contaminate results across passes (clearAccum)" in {
    // Pass 1: diagonal weights [[10,0],[0,10]], activations [5,0] → col0=50, col1=0
    // Pass 2: same weights, activations [0,0] → must produce [0,0], not stale accumulators
    test(new Top(2, 2, 8, 64)) { dut =>
      // Write weights: row-major [10, 0, 0, 10]
      val weights = Seq(10, 0, 0, 10)
      for ((w, i) <- weights.zipWithIndex) {
        dut.io.weightWriteEnable.poke(true.B)
        dut.io.weightWriteAddr.poke(i.U)
        dut.io.weightWriteData.poke(w.U)
        dut.clock.step()
      }
      dut.io.weightWriteEnable.poke(false.B)

      // Pass 1: activations [5, 0]
      dut.io.activationWriteEnable.poke(true.B)
      dut.io.activationWriteAddr.poke(0.U)
      dut.io.activationWriteData.poke(5.U)
      dut.clock.step()
      dut.io.activationWriteAddr.poke(1.U)
      dut.io.activationWriteData.poke(0.U)
      dut.clock.step()
      dut.io.activationWriteEnable.poke(false.B)
      dut.clock.step(2)

      dut.io.start.poke(true.B)
      dut.io.loadWeights.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)
      dut.io.loadWeights.poke(false.B)
      var cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 30) {
        dut.clock.step(); cycles += 1
      }
      dut.io.done.expect(true.B)
      dut.clock.step(3)

      // Pass 2: activations [0, 0] — result must be zero, not contaminated by pass 1
      dut.io.activationWriteEnable.poke(true.B)
      dut.io.activationWriteAddr.poke(0.U)
      dut.io.activationWriteData.poke(0.U)
      dut.clock.step()
      dut.io.activationWriteAddr.poke(1.U)
      dut.io.activationWriteData.poke(0.U)
      dut.clock.step()
      dut.io.activationWriteEnable.poke(false.B)
      dut.clock.step(2)

      // Reuse weights already in PEs (no loadWeights)
      dut.io.start.poke(true.B)
      dut.io.loadWeights.poke(false.B)
      dut.clock.step()
      dut.io.start.poke(false.B)
      cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 30) {
        dut.clock.step(); cycles += 1
      }
      dut.io.done.expect(true.B)
      dut.clock.step(2)

      dut.io.resultReadEnable.poke(true.B)
      dut.io.resultReadAddr.poke(0.U)
      dut.clock.step()
      val result0 = dut.io.resultReadData.peek().litValue
      dut.io.resultReadAddr.poke(1.U)
      dut.clock.step()
      val result1 = dut.io.resultReadData.peek().litValue
      dut.io.resultReadEnable.poke(false.B)

      assert(result0 == 0, s"Pass 2 col 0: expected 0, got $result0")
      assert(result1 == 0, s"Pass 2 col 1: expected 0, got $result1")
    }
  }

  it should "accumulate results across two activation tiles" in {
    // Identity weight matrix (4x4), so result column c = sum of activations for that column
    // Tile 0: activations [1,2,3,4] → partial sums [1,2,3,4]
    // Tile 1: activations [5,6,7,8], accumulate=true → final [1+5, 2+6, 3+7, 4+8] = [6,8,10,12]
    test(new Top(4, 4, 8, 256)) { dut =>
      // Load identity weight matrix (row-major: row0=[1,0,0,0], row1=[0,1,0,0], ...)
      for (r <- 0 until 4; c <- 0 until 4) {
        dut.io.weightWriteEnable.poke(true.B)
        dut.io.weightWriteAddr.poke((r * 4 + c).U)
        dut.io.weightWriteData.poke((if (r == c) 1 else 0).U)
        dut.clock.step()
      }
      dut.io.weightWriteEnable.poke(false.B)

      // Tile 0: activations [1,2,3,4], accumulate=false (first tile)
      for (i <- 0 until 4) {
        dut.io.activationWriteEnable.poke(true.B)
        dut.io.activationWriteAddr.poke(i.U)
        dut.io.activationWriteData.poke((i + 1).U)
        dut.clock.step()
      }
      dut.io.activationWriteEnable.poke(false.B)
      dut.clock.step(2)

      dut.io.start.poke(true.B)
      dut.io.loadWeights.poke(true.B)
      dut.io.accumulate.poke(false.B)
      dut.clock.step()
      dut.io.start.poke(false.B)
      dut.io.loadWeights.poke(false.B)
      var cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 40) {
        dut.clock.step(); cycles += 1
      }
      dut.io.done.expect(true.B)
      dut.clock.step(3)

      // Tile 1: activations [5,6,7,8], accumulate=true
      for (i <- 0 until 4) {
        dut.io.activationWriteEnable.poke(true.B)
        dut.io.activationWriteAddr.poke(i.U)
        dut.io.activationWriteData.poke((i + 5).U)
        dut.clock.step()
      }
      dut.io.activationWriteEnable.poke(false.B)
      dut.clock.step(2)

      dut.io.start.poke(true.B)
      dut.io.loadWeights.poke(false.B)
      dut.io.accumulate.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)
      cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 40) {
        dut.clock.step(); cycles += 1
      }
      dut.io.done.expect(true.B)
      // Step past the register-commit cycle before de-asserting accumulate;
      // tileAccumRegs updates on the clock edge immediately following done.
      dut.clock.step(2)
      dut.io.accumulate.poke(false.B)

      val expected = Seq(6, 8, 10, 12)
      dut.io.resultReadEnable.poke(true.B)
      for (c <- 0 until 4) {
        dut.io.resultReadAddr.poke(c.U)
        dut.clock.step()
        val result = dut.io.resultReadData.peek().litValue
        assert(result == expected(c), s"Tile col $c: expected ${expected(c)}, got $result")
      }
      dut.io.resultReadEnable.poke(false.B)
    }
  }
}
