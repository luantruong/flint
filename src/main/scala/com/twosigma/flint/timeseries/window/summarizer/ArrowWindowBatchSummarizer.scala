/*
 *  Copyright 2018 TWO SIGMA OPEN SOURCE, LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.twosigma.flint.timeseries.window.summarizer

import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.util

import com.twosigma.flint.arrow.{ ArrowUtils, ArrowWriter }
import com.twosigma.flint.rdd.function.window.summarizer.WindowBatchSummarizer
import org.apache.arrow.memory.{ BufferAllocator, RootAllocator }
import org.apache.arrow.vector.{ IntVector, VectorSchemaRoot }
import org.apache.arrow.vector.ipc.ArrowFileWriter
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{ GenericInternalRow, UnsafeProjection }
import org.apache.spark.sql.catalyst.util.GenericArrayData
import org.apache.spark.sql.types._

import scala.collection.JavaConverters._

/**
 * @param leftRows     Left rows. Updated every time [[BaseWindowBatchSummarizer.addLeft]] is called
 * @param rightRows    Right rows in a continuous array.
 *                     Null until [[BaseWindowBatchSummarizer.finalizeRightRowsAndRebaseIndex]] is called.
 * @param rightRowsMap Right rows grouped by SK. Updated every time [[BaseWindowBatchSummarizer.addRight()]] is called.
 *                     This is an append only data structure. [[BaseWindowBatchSummarizer.subtractRight()]]
 *                     doesn't remove right rows from it.
 * @param beginIndices Begin window index for each left row. Updated in [[BaseWindowBatchSummarizer.commitLeft()]]
 *                     and rebased in [[BaseWindowBatchSummarizer.finalizeRightRowsAndRebaseIndex]]
 * @param endIndices Similar to [[beginIndices]]
 * @param sks SK for each left row. This is used in [[BaseWindowBatchSummarizer.finalizeRightRowsAndRebaseIndex]]
 *            to rebase begin/end indices.
 * @param currentBeginIndices Single value per SK to track the current window index. The current begin index for window
 *                            of each SK. Updated during window calculation.
 * @param currentEndIndices Similar to [[currentEndIndices]]
 */
private[flint] case class WindowBatchSummarizerState(
  val leftRows: util.ArrayList[InternalRow],
  var rightRows: util.ArrayList[InternalRow],
  val rightRowsMap: util.LinkedHashMap[Any, util.ArrayList[InternalRow]],
  val beginIndices: util.ArrayList[Int],
  val endIndices: util.ArrayList[Int],
  val sks: util.ArrayList[Any],
  val currentBeginIndices: util.HashMap[Any, Int],
  val currentEndIndices: util.HashMap[Any, Int]
) {
  def this() {
    this(
      new util.ArrayList[InternalRow](),
      null,
      new util.LinkedHashMap[Any, util.ArrayList[InternalRow]](),
      new util.ArrayList[Int](),
      new util.ArrayList[Int](),
      new util.ArrayList[Any](),
      new util.HashMap[Any, Int](),
      new util.HashMap[Any, Int]()
    )
  }
}

private[flint] abstract class BaseWindowBatchSummarizer(val leftSchema: StructType, val rightSchema: StructType)
  extends WindowBatchSummarizer[Long, Any, InternalRow, WindowBatchSummarizerState, InternalRow] {

  private def initSk(u: WindowBatchSummarizerState, sk: Any): util.ArrayList[InternalRow] = {
    val rows = new util.ArrayList[InternalRow]()
    u.rightRowsMap.put(sk, rows)
    u.currentBeginIndices.put(sk, 0)
    u.currentEndIndices.put(sk, 0)
    rows
  }

  override def zero(): WindowBatchSummarizerState = {
    new WindowBatchSummarizerState()
  }

  override def addLeft(u: WindowBatchSummarizerState, sk: Any, row: InternalRow): Unit = {
    require(u.leftRows.size() == u.sks.size())
    require(u.sks.size() == u.beginIndices.size())
    require(u.beginIndices.size() == u.endIndices.size())

    u.leftRows.add(row)
    u.sks.add(sk)
  }

  override def commitLeft(u: WindowBatchSummarizerState, sk: Any, v: InternalRow): Unit = {
    val begin = u.currentBeginIndices.getOrDefault(sk, 0)
    val end = u.currentEndIndices.getOrDefault(sk, 0)

    u.beginIndices.add(begin)
    u.endIndices.add(end)
  }

  override def addRight(u: WindowBatchSummarizerState, sk: Any, v: InternalRow): Unit = {
    var rows = u.rightRowsMap.get(sk)
    if (rows == null) {
      rows = initSk(u, sk)
    }
    rows.add(v)
    // Use "compute" here should be faster but it's hard to use Java lambda function here
    u.currentEndIndices.put(sk, u.currentEndIndices.get(sk) + 1)
  }

  override def subtractRight(u: WindowBatchSummarizerState, sk: Any, v: InternalRow): Unit = {
    u.currentBeginIndices.put(sk, u.currentBeginIndices.get(sk) + 1)
  }

  /**
   * Concat right rows for secondary keys to a single array.
   * Also compute the base index for each secondary key and add base index to begin and end index for each row.
   */
  private def finalizeRightRowsAndRebaseIndex(u: WindowBatchSummarizerState): Unit = {
    require(u.rightRows == null)
    u.rightRows = new util.ArrayList[InternalRow]()

    val allSks = new util.HashSet[Any](u.sks)
    val count = u.leftRows.size()

    var baseIndex = 0
    val baseIndexMap = new util.HashMap[Any, Int]()

    // Don't serialize the right rows for Sks that doesn't appear in the left rows
    for ((sk, rows) <- u.rightRowsMap.asScala if allSks.contains(sk)) {
      u.rightRows.addAll(rows)
      baseIndexMap.put(sk, baseIndex)
      baseIndex += rows.size()
    }

    var i = 0
    while (i < count) {
      val baseIndex = baseIndexMap.get(u.sks.get(i))
      u.beginIndices.set(i, u.beginIndices.get(i) + baseIndex)
      u.endIndices.set(i, u.endIndices.get(i) + baseIndex)
      i += 1
    }
  }

  override def render(u: WindowBatchSummarizerState): InternalRow = {
    finalizeRightRowsAndRebaseIndex(u)
    renderOutput(u)
  }

  // Abstract methods
  val schema: StructType
  def renderOutput(u: WindowBatchSummarizerState): InternalRow
}

/**
 * A summarizer that renders result to Array of rows. This should only be used for testing purpose.
 */
private[flint] case class ArrayWindowBatchSummarizer(
  override val leftSchema: StructType,
  override val rightSchema: StructType
) extends BaseWindowBatchSummarizer(leftSchema, rightSchema) {
  override val schema = StructType(
    Seq(
      StructField("__window_leftBatch", ArrayType(leftSchema)),
      StructField("__window_rightBatch", ArrayType(rightSchema)),
      StructField(
        "__window_indices",
        ArrayType(StructType(Seq(StructField("begin", IntegerType), StructField("end", IntegerType))))
      )
    )
  )

  override def renderOutput(
    u: WindowBatchSummarizerState
  ): InternalRow = {
    val indexRows = (u.beginIndices.asScala zip u.endIndices.asScala).map {
      case (begin, end) =>
        val values: Array[Any] = Array(begin, end)
        new GenericInternalRow(values)
    }

    val values: Array[Any] = Array(
      new GenericArrayData(u.leftRows.toArray),
      new GenericArrayData(u.rightRows.toArray),
      new GenericArrayData(indexRows.toArray)
    )

    new GenericInternalRow(values)
  }
}

object ArrowWindowBatchSummarizer {
  val baseRowsColumnName = "__window_baseRows"
  val leftBatchColumnName = "__window_leftBatch"
  val leftLengthColumnName = "__window_leftLength"
  val rightBatchColumnName = "__window_rightBatch"
  val rightLengthColumnName = "__window_rightLength"
  val indicesColumnName = "__window_indices"

  val beginIndexColumnName = "begin"
  val endIndexColumnName = "end"
}

private[flint] case class ArrowWindowBatchSummarizer(
  override val leftSchema: StructType,
  leftPrunedSchema: StructType,
  override val rightSchema: StructType,
  rightPrunedSchema: StructType
) extends BaseWindowBatchSummarizer(leftSchema, rightSchema) {

  import ArrowWindowBatchSummarizer._

  override val schema = StructType(
    Seq(
      StructField(baseRowsColumnName, ArrayType(leftSchema)),
      StructField(leftBatchColumnName, BinaryType),
      StructField(leftLengthColumnName, IntegerType),
      StructField(rightBatchColumnName, BinaryType),
      StructField(rightLengthColumnName, IntegerType),
      StructField(indicesColumnName, BinaryType)
    )
  )

  private def serializeRows(
    rows: util.ArrayList[InternalRow],
    originalSchema: StructType,
    prunedSchema: StructType,
    allocator: BufferAllocator
  ): Array[Byte] = {
    val allocator = new RootAllocator(Long.MaxValue)
    val arrowSchema = ArrowUtils.toArrowSchema(prunedSchema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    val out = new ByteArrayOutputStream()
    val writer = new ArrowFileWriter(root, null, Channels.newChannel(out))
    val arrowWriter = ArrowWriter.create(originalSchema, prunedSchema, root)

    try {
      val rowIter = rows.iterator()
      while (rowIter.hasNext) {
        val row = rowIter.next()
        arrowWriter.write(row)
      }

      arrowWriter.finish()
      writer.writeBatch()

    } finally {
      writer.close()
      root.close()
      allocator.close()
    }

    out.toByteArray
  }

  private def serializeIndices(
    beginIndices: util.ArrayList[Int],
    endIndices: util.ArrayList[Int],
    allocator: BufferAllocator
  ): Array[Byte] = {
    val schema =
      StructType(
        Seq(
          StructField(beginIndexColumnName, IntegerType),
          StructField(endIndexColumnName, IntegerType)
        )
      )
    val arrowSchema = ArrowUtils.toArrowSchema(schema)
    val rowCount = beginIndices.size()

    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    val vectors = root.getFieldVectors
    val beginIndexVector = vectors.get(0).asInstanceOf[IntVector]
    val endIndexVector = vectors.get(1).asInstanceOf[IntVector]
    beginIndexVector.allocateNew()
    endIndexVector.allocateNew()

    var j = 0
    while (j < beginIndices.size()) {
      beginIndexVector.setSafe(j, beginIndices.get(j))
      j += 1
    }
    beginIndexVector.setValueCount(rowCount)

    j = 0
    while (j < endIndices.size()) {
      endIndexVector.setSafe(j, endIndices.get(j))
      j += 1
    }
    endIndexVector.setValueCount(rowCount)

    root.setRowCount(rowCount)

    val out = new ByteArrayOutputStream()
    val writer = new ArrowFileWriter(root, null, Channels.newChannel(out))
    writer.writeBatch()

    writer.close()
    root.close()

    out.toByteArray
  }

  override def renderOutput(u: WindowBatchSummarizerState): InternalRow = {
    // Do this to call the correct constructor in GenericArrayData
    val baseRows: Array[Any] = u.leftRows.toArray.asInstanceOf[Array[Any]]
    // This is used for reconstructing the rows after computing the window value
    val baseRowsData = new GenericArrayData(baseRows)

    val allocator = new RootAllocator(Int.MaxValue)
    val (leftLength, leftBatch) = if (leftPrunedSchema.length > 0) {
      (u.leftRows.size, serializeRows(u.leftRows, leftSchema, leftPrunedSchema, allocator))
    } else {
      (0, null)
    }
    val rightBatch = serializeRows(u.rightRows, rightSchema, rightPrunedSchema, allocator)
    val indicesArrowBytes = serializeIndices(u.beginIndices, u.endIndices, allocator)
    allocator.close()

    val values: Array[Any] = Array(
      baseRowsData,
      leftBatch,
      leftLength,
      rightBatch,
      u.rightRows.size,
      indicesArrowBytes
    )
    new GenericInternalRow(values)
  }
}