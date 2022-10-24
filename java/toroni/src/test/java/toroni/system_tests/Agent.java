/*
 * Copyright 2022 VMware, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

package toroni.system_tests;

import static com.sun.jna.platform.linux.Fcntl.S_IRUSR;
import static com.sun.jna.platform.linux.Fcntl.S_IWUSR;

import com.sun.jna.Pointer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import toroni.rmp.BackPressureCallback;
import toroni.rmp.ByteRingBuffer;
import toroni.tp.AsyncWriter;
import toroni.tp.ChannelReader;
import toroni.tp.MpscMessageQueue;
import toroni.tp.Reader.ChannelReaderEventType;
import toroni.traits.MulticastUdpNotification;
import toroni.traits.PosixSharedMemoryFactory;
import toroni.traits.PthreadRobustMutex;
import toroni.traits.RobustMutex;
import toroni.traits.SharedMemory;

public class Agent {

  public static final long MS_NANOSEC = 1000 * 1000;

  public enum EXIT_CODE {
    EC_SUCCESS, EC_UNKNOWN_ARG, EC_READER_EXPIRED
  };

  public static final Logger LOGGER = Logger.getLogger(Agent.class.getName());

  static final SharedMemory ringBufShm;
  static final SharedMemory readerInfoShm;
  static final SharedMemory statsShm;
  static final ByteRingBuffer ringBuf;
  static final toroni.tp.ReaderInfo readerInfo;
  static final toroni.rmp.ReaderInfo rmpReaderInfo;
  static final AgentStats agentStats;
  static final MulticastUdpNotification udpNotification;

  static {
    try {
      ringBufShm = PosixSharedMemoryFactory.createOrOpen(
          "toroni-burst-rb",
          ByteRingBuffer.size(Config.getOptRingBufSize(), PthreadRobustMutex.getSize()),
          S_IRUSR | S_IWUSR);

      readerInfoShm = PosixSharedMemoryFactory.createOrOpen(
          "toroni-burst-ri",
          toroni.tp.ReaderInfo.size((short) Config.getOptMaxReaders(), PthreadRobustMutex.getSize()),
          S_IRUSR | S_IWUSR);

      statsShm = PosixSharedMemoryFactory.createOrOpen(
          "toroni-burst-s",
          AgentStats.size(),
          S_IRUSR | S_IWUSR);

      RobustMutex mtx = new PthreadRobustMutex(new Pointer(Pointer.nativeValue(ringBufShm.ptr()) + Long.BYTES));
      mtx.initialize();
      ringBuf = new ByteRingBuffer(
          ringBufShm.ptr(),
          Config.getOptRingBufSize(),
          mtx);

      short maxReaders = (short) Config.getOptMaxReaders();

      RobustMutex[] locks = new PthreadRobustMutex[maxReaders];
      for (int i = 0; i < maxReaders; i++) {
        long lockAddress = Pointer.nativeValue(readerInfoShm.ptr())
            + toroni.tp.ReaderInfo.RMP_READER_INFO_OFFSET
            + toroni.rmp.ReaderInfo.FIRST_INFO_OFFSET
            + i * toroni.rmp.ReaderInfoInfo.size(PthreadRobustMutex.getSize());

        locks[i] = new PthreadRobustMutex(new Pointer(lockAddress));
      }

      readerInfo = new toroni.tp.ReaderInfo(readerInfoShm.ptr(), maxReaders, locks);

      rmpReaderInfo = readerInfo.rmpReaderInfo;

      agentStats = new AgentStats(statsShm.ptr());

      udpNotification = new MulticastUdpNotification("224.1.1.1", (short) 3334, "127.0.0.1");
    } catch (Exception e) {
      throw new Error(e);
    }
  }

  public static void runInit() {
    long startInitMs = System.currentTimeMillis();

    LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "init start");
    ringBuf.initialize();
    LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "init ringBuf done");
    readerInfo.initialize();
    LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "init readerInfo done");
    agentStats.initialize();
    LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "init agentStats done");
    LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "init end");

    LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "init took "
        + (System.currentTimeMillis() - startInitMs) + " (ms)");
  }

  public static void runWriter(final TestPolicy testPolicy) {
    LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "writer start");
    long startMs = System.currentTimeMillis();

    final AtomicLong notificationNs = new AtomicLong(0);
    Runnable notifyCb = new Runnable() {

      @Override
      public void run() {
        LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "write notify start");
        long startNs = System.nanoTime();
        udpNotification.sendNotification();
        notificationNs.set(notificationNs.addAndGet(System.nanoTime() - startNs));
        LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "writer notify end");
      }

    };

    MpscMessageQueue<byte[]> msqQueue = new MpscMessageQueue<>();
    final AtomicReference<Runnable> wrk = new AtomicReference<>();
    AsyncWriter writer = AsyncWriter.create(
        ringBuf, readerInfo,
        new AsyncWriter.EnqueueMsgFn() {

          @Override
          public boolean run(byte[] topicMsg) {
            return msqQueue.enqueue(topicMsg);
          }

        },
        new AsyncWriter.DrainMsgFn() {

          @Override
          public ArrayList<byte[]> run() {
            return msqQueue.drain();
          }

        },
        new AsyncWriter.EnqueueWorkFn() {

          @Override
          public void run(Runnable workFn) {
            wrk.set(workFn);
          }

        },
        new BackPressureCallback() {

          @Override
          public boolean writeOrWait(long bpPos, long freePos) {
            LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "writer backpressure");
            testPolicy.onBackpressure();
            return false;
          }

        },
        notifyCb);

    testPolicy.initWriter(writer);
    testPolicy.syncAllWriters();

    LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "writer starting took "
        + (System.currentTimeMillis() - startMs) + " (ms)");

    LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "writer posting start");

    long postingStartMs = System.currentTimeMillis();
    while (testPolicy.postMore()) {
      testPolicy.post();
    }

    LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "writer posting end");

    long startNs = System.nanoTime();
    wrk.get().run();
    LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "posting took "
        + (System.currentTimeMillis() - postingStartMs) + " (ms)");

    long elapsedTimeInNs = System.nanoTime() - startNs;

    agentStats.incWriterDurationNsSum(elapsedTimeInNs);
    agentStats.incNotificationNsSum(notificationNs.get());

    LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "writer end");
  }

  public static void runReader(TestPolicy testPolicy) {
    LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "reader start");

    long startMs = System.currentTimeMillis();

    AtomicBoolean readerExpired = new AtomicBoolean(false);

    toroni.tp.Reader reader = toroni.tp.Reader.create(
        ringBuf, readerInfo,
        new toroni.tp.Reader.EnqueueSerialFn() {

          @Override
          public void run(Runnable fn) {
            fn.run();
          }

        },
        new toroni.tp.Reader.EnqueueSerialFn() {

          @Override
          public void run(Runnable fn) {
            fn.run();
          }

        },
        new toroni.tp.Reader.ChannelReaderEventCallback() {

          @Override
          public void run(ChannelReaderEventType et) {
            if (et == ChannelReaderEventType.ALL_CHANNEL_READERS_EXPIRES) {
              LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "reader expired");
              readerExpired.set(true);
              System.exit(EXIT_CODE.EC_READER_EXPIRED.ordinal());
            }
          }

        });

    reader.createChannelReader(
        "channel",
        new ChannelReader.Handler() {

          @Override
          public void run(ByteBuffer data) {
            testPolicy.onChannelReader(data);
          }

        },
        false);

    long runCount = 0;
    agentStats.incReadersReady(1);

    LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "reader starting took "
        + (System.currentTimeMillis() - startMs) + " (ms)");
    LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "reader started");

    while (testPolicy.readMore()) {
      LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "reader wait start");
      udpNotification.waitForNotification();
      LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "reader wait end");

      long readingStartMs = System.currentTimeMillis();
      LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "reader run start");
      runCount++;
      reader.run();
      LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "readeing took "
          + (System.currentTimeMillis() - readingStartMs) + " (ms)");
      LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "reader run end");
    }

    reader.destroy();

    agentStats.incReaderRuns(runCount);
    LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + "reader end");
  }

  public static void runUnlink() {
    try {
      ringBufShm.unlink();
      readerInfoShm.unlink();
      statsShm.unlink();
    } catch (Exception e) {
      throw new Error(e);
    }
  }

  public static long runExpired() {
    return readerInfo.rmpReaderInfo.getStatExpiredReaders();
  }

  public static void runWaitReadersReady(long expReaders) {
    while (agentStats.getReadersReady() < expReaders)
      ;
  }

  public static void runResetIter() {
    agentStats.setWritersReady(0);
    agentStats.setReadersReady(0);
  }

  public static void runStat() {
    System.out.println("Backpressure count: " + ringBuf.getStatBackPressureCount());
    System.out.println("Expired readers count: " + readerInfo.rmpReaderInfo.getStatExpiredReaders());
    System.out.println("Write notifications count: " + ringBuf.getStatNotificationCount());
    System.out.println("Freepos: " + ringBuf.getFreePos());

    short[] minMax = rmpReaderInfo.getActiveRange();
    short amin = minMax[0], amax = minMax[1];

    System.out.println("Active reader range: [" + amin + ", " + amax + ")");
    System.out.println("Sum latency: " + (double) agentStats.getLatencyNsSum() / MS_NANOSEC + " (ms)");
    System.out.println("Sum throughput-first-last-duration: " +
        (double) agentStats.getFirstLastDurationNsSum() / MS_NANOSEC + " (ms)");
    System.out.println("Avg-latency: " + LatencyTest.avgLatencyMs() + " (ms)");
    System.out.println("Avg-throughput-first-last: " +
        ThroughputTest.avgThroughputKMsgSec() + " (Kmsg/sec)");
    System.out.println("Readers ready: " + agentStats.getReadersReady());
    System.out.println("Recieved messages: " + agentStats.getMsgCount());
  }

  public static int runCmpReceivedMessages(long arg2) {
    return (agentStats.getMsgCount() == arg2 ? 0 : 1);
  }

  public static int main(String[] args, TestPolicy testPolicy) {
    if (args.length == 0) {
      System.out.println("unknown cmd");
      return EXIT_CODE.EC_UNKNOWN_ARG.ordinal();
    }

    if (args[0].equals("init")) {
      runInit();
    } else if (args[0].equals("write")) {
      runWriter(testPolicy);
    } else if (args[0].equals("read")) {
      runReader(testPolicy);
    } else if (args[0].equals("stat")) {
      runStat();
    } else if (args[0].equals("result")) {
      testPolicy.result();
    } else if (args[0].equals("unlink")) {
      runUnlink();
    } else if (args[0].equals("expired")) {
      return (int) runExpired();
    } else if (args[0].equals("waitreaders")) {
      runWaitReadersReady(Config.getOptReaders());
    } else if (args[0].equals("cmprcved")) {
      return runCmpReceivedMessages(Long.parseLong(args[1]));
    } else if (args[0].equals("resetiter")) {
      runResetIter();
    } else {
      System.out.println("unknown cmd");
      return EXIT_CODE.EC_UNKNOWN_ARG.ordinal();
    }

    return EXIT_CODE.EC_SUCCESS.ordinal();
  }

  public static void main(String[] args) {
    LOGGER.setLevel(Level.OFF);

    switch (Config.getOptTestFavour()) {
      case FIRST_LAST_DURATION: {
        ThroughputTest throughputTest = new ThroughputTest();
        LOGGER
            .info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + String.valueOf(main(args, throughputTest)));
        break;
      }
      case LATENCY: {
        LatencyTest latencyTest = new LatencyTest();
        LOGGER.info("PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " +
            String.valueOf(main(args, latencyTest)));
        latencyTest.destroy();
        break;
      }
      case ROBUST_WRITER: {
        RobustWriterTest robustWriterTest = new RobustWriterTest();
        LOGGER
            .info(
                "PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + String.valueOf(main(args, robustWriterTest)));
        break;
      }
      case ROBUST_READER: {
        RobustReaderTest robustReaderTest = new RobustReaderTest();
        LOGGER
            .info(
                "PROCESS_ID [ " + ProcessHandle.current().pid() + " ] " + String.valueOf(main(args, robustReaderTest)));
        break;
      }
      default: {
        System.out.println("unknown test flavour");
        System.exit(EXIT_CODE.EC_UNKNOWN_ARG.ordinal());
      }
    }
  }

}
