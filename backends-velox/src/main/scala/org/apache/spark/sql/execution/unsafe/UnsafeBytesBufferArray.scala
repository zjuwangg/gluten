/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.execution.unsafe

import org.apache.spark.internal.Logging
import org.apache.spark.memory.{MemoryConsumer, MemoryMode, TaskMemoryManager}
import org.apache.spark.unsafe.Platform
import org.apache.spark.unsafe.array.LongArray

import java.security.MessageDigest

/**
 * Used to store broadcast variable off-heap memory for broadcast variable. The underlying data
 * structure is a LongArray allocated in offheap memory.
 *
 * @param arraySize
 *   underlying array[array[byte]]'s length
 * @param bytesBufferLengths
 *   underlying array[array[byte]] per bytesBuffer length
 * @param totalBytes
 *   all bytesBuffer's length plus together
 */
// scalastyle:off no.finalize
case class UnsafeBytesBufferArray(
    arraySize: Int,
    bytesBufferLengths: Array[Int],
    totalBytes: Long,
    tmm: TaskMemoryManager)
  extends MemoryConsumer(tmm, MemoryMode.OFF_HEAP)
  with Logging {

  {
    assert(arraySize == bytesBufferLengths.length)
    assert(totalBytes >= 0)
  }

  /**
   * A single array to store all bytesBufferArray's value, it's inited once when first time get
   * accessed.
   */
  private var longArray: LongArray = _

  /** Index the start of each byteBuffer's offset to underlying LongArray's initial position. */
  private val bytesBufferOffset = if (bytesBufferLengths.isEmpty) {
    new Array(0)
  } else {
    bytesBufferLengths.init.scanLeft(0)(_ + _)
  }

  override def spill(l: Long, memoryConsumer: MemoryConsumer): Long = 0L

  /**
   * Put bytesBuffer at specified array index.
   * @param index
   * @param bytesBuffer
   */
  def putBytesBuffer(index: Int, bytesBuffer: Array[Byte]): Unit = this.synchronized {
    assert(index < arraySize)
    assert(bytesBuffer.length == bytesBufferLengths(index))
    // first to allocate underlying long array
    if (null == longArray && index == 0) {
      log.debug(s"allocate array $totalBytes, actual longArray size ${(totalBytes + 7) / 8}")
      longArray = allocateArray((totalBytes + 7) / 8)
    }
    if (log.isDebugEnabled) {
      log.debug(s"put bytesBuffer at index $index bytesBuffer's length is ${bytesBuffer.length}")
      log.debug(
        s"bytesBuffer at index $index " +
          s"digest ${calculateMD5(bytesBuffer).mkString("Array(", ", ", ")")}")
    }
    Platform.copyMemory(
      bytesBuffer,
      Platform.BYTE_ARRAY_OFFSET,
      longArray.getBaseObject,
      longArray.getBaseOffset + bytesBufferOffset(index),
      bytesBufferLengths(index))
  }

  /**
   * Get bytesBuffer at specified index.
   * @param index
   * @return
   */
  def getBytesBuffer(index: Int): Array[Byte] = {
    assert(index < arraySize)
    if (null == longArray) {
      return new Array[Byte](0)
    }
    val bytes = new Array[Byte](bytesBufferLengths(index))
    log.debug(s"get bytesBuffer at index $index bytesBuffer length ${bytes.length}")
    Platform.copyMemory(
      longArray.getBaseObject,
      longArray.getBaseOffset + bytesBufferOffset(index),
      bytes,
      Platform.BYTE_ARRAY_OFFSET,
      bytesBufferLengths(index))
    if (log.isDebugEnabled) {
      log.debug(
        s"get bytesBuffer at index $index " +
          s"digest ${calculateMD5(bytes).mkString("Array(", ", ", ")")}")
    }
    bytes
  }

  /**
   * Get the bytesBuffer memory address and length at specified index, usually used when read memory
   * direct from offheap.
   *
   * @param index
   * @return
   */
  def getBytesBufferOffsetAndLength(index: Int): (Long, Int) = {
    assert(index < arraySize)
    assert(longArray != null, "The broadcast data in offheap should not be null!")
    val offset = longArray.getBaseOffset + bytesBufferOffset(index)
    val length = bytesBufferLengths(index)
    (offset, length)
  }

  /**
   * It's needed once the broadcast variable is garbage collected. Since now, we don't have an
   * elegant way to free the underlying memory in offheap.
   */
  override def finalize(): Unit = {
    try {
      if (longArray != null) {
        log.debug(s"BytesArrayInOffheap finalize $arraySize")
        freeArray(longArray)
        longArray = null
      }
    } finally {
      super.finalize()
    }
  }

  /**
   * Used to debug input/output bytes.
   *
   * @param bytesBuffer
   * @return
   */
  private def calculateMD5(bytesBuffer: Array[Byte]): Array[Byte] = try {
    val md = MessageDigest.getInstance("MD5")
    md.digest(bytesBuffer)
  } catch {
    case e: Throwable =>
      log.warn("error when calculateMD5", e)
      new Array[Byte](0)
  }
}
// scalastyle:on no.finalize