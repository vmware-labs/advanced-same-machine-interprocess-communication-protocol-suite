/*
 * Copyright 2022 VMware, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

package toroni.tp;

import com.sun.jna.Pointer;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import com.sun.jna.Memory;

import toroni.rmp.BackPressureCallback;
import toroni.rmp.ByteRingBuffer;
import toroni.traits.PthreadRobustMutex;
import toroni.traits.RobustMutex;

class AsyncWriterTest {

  private final int MUTEX_SIZE_BYTES = 40;

  private AsyncWriter.EnqueueMsgFn mockEnqueueMsgFn = Mockito.mock(AsyncWriter.EnqueueMsgFn.class);
  private AsyncWriter.DrainMsgFn mockDrainMsgFn = Mockito.mock(AsyncWriter.DrainMsgFn.class);
  private AsyncWriter.EnqueueWorkFn mockEnqueueWorkFn = Mockito.mock(AsyncWriter.EnqueueWorkFn.class);
  private Runnable mockNotifyFn = Mockito.mock(Runnable.class);
  private BackPressureCallback mockBpFn = Mockito.mock(BackPressureCallback.class);

  ByteRingBuffer initRingBuf() {
    long bufSize = 1024;

    Pointer mtxPointer = new Memory(MUTEX_SIZE_BYTES);
    RobustMutex mtx = new PthreadRobustMutex(mtxPointer);
    mtx.initialize();

    Pointer ringBufPointer = new Memory(ByteRingBuffer.size(bufSize, PthreadRobustMutex.getSize()));
    ByteRingBuffer ringBuf = new ByteRingBuffer(ringBufPointer, bufSize, mtx);

    ringBuf.initialize();
    return ringBuf;
  }

  ReaderInfo initReaderInfo() {
    short maxReaders = 3;

    Pointer[] mtxPointers = {
        new Memory(MUTEX_SIZE_BYTES),
        new Memory(MUTEX_SIZE_BYTES),
        new Memory(MUTEX_SIZE_BYTES)
    };

    RobustMutex[] locks = new PthreadRobustMutex[maxReaders];
    for (int i = 0; i < maxReaders; i++) {
      locks[i] = new PthreadRobustMutex(mtxPointers[i]);
      locks[i].initialize();
    }

    Pointer readerInfoPointer = new Memory(ReaderInfo.size(maxReaders, MUTEX_SIZE_BYTES));

    ReaderInfo readerInfo = new ReaderInfo(readerInfoPointer, maxReaders, locks);
    readerInfo.initialize();

    return readerInfo;
  }

  @Test
  void postEnqueueWhenToldTo() {
    ByteRingBuffer ringBuf = initRingBuf();
    ReaderInfo readerInfo = initReaderInfo();

    AsyncWriter writer = AsyncWriter.create(ringBuf, readerInfo,
        mockEnqueueMsgFn, mockDrainMsgFn, mockEnqueueWorkFn, mockBpFn, mockNotifyFn);

    byte[] msg = new byte[0];
    try {
      msg = writer.createMessage("1", "1", true);
    } catch (Exception e) {
      assert (false);
    }

    when(mockEnqueueMsgFn.run(any())).thenReturn(true, true, false);

    writer.post(msg);
    writer.post(msg);
    writer.post(msg);

    InOrder orderVerifier = Mockito.inOrder(mockEnqueueMsgFn, mockEnqueueWorkFn,
        mockNotifyFn, mockDrainMsgFn, mockBpFn);
    orderVerifier.verify(mockEnqueueMsgFn).run(any());
    orderVerifier.verify(mockEnqueueWorkFn, times(1)).run(any());
    orderVerifier.verify(mockEnqueueMsgFn).run(any());
    orderVerifier.verify(mockEnqueueWorkFn, times(1)).run(any());
    orderVerifier.verify(mockEnqueueMsgFn).run(any());
    orderVerifier.verify(mockEnqueueWorkFn, times(0)).run(any());
    orderVerifier.verify(mockNotifyFn, times(0)).run();
    orderVerifier.verify(mockDrainMsgFn, times(0)).run();
    orderVerifier.verify(mockBpFn, times(0)).writeOrWait(anyLong(), anyLong());

  }

  @Test
  void postDrainNotify() {
    ByteRingBuffer ringBuf = initRingBuf();
    ReaderInfo readerInfo = initReaderInfo();

    AsyncWriter writer = AsyncWriter.create(ringBuf, readerInfo, mockEnqueueMsgFn, mockDrainMsgFn,
        new AsyncWriter.EnqueueWorkFn() {

          @Override
          public void run(Runnable workFn) {
            workFn.run();
          }

        }, mockBpFn, mockNotifyFn);

    byte[] msg = new byte[0];
    try {
      msg = writer.createMessage("1", "1", true);
    } catch (Exception e) {
      assert (false);
    }

    ArrayList<byte[]> emptyQueue = new ArrayList<>();

    ArrayList<byte[]> queueOneEl = new ArrayList<>();
    queueOneEl.add(msg);

    when(mockEnqueueMsgFn.run(any())).thenReturn(true);
    when(mockDrainMsgFn.run()).thenReturn(queueOneEl, emptyQueue);

    writer.post(msg);

    InOrder orderVerifier = Mockito.inOrder(mockEnqueueMsgFn, mockDrainMsgFn, mockNotifyFn, mockBpFn);
    orderVerifier.verify(mockNotifyFn, times(1)).run();
    orderVerifier.verify(mockBpFn, times(0)).writeOrWait(anyLong(), anyLong());

    assertNotEquals(0, ringBuf.getFreePos());
  }

}