#!/bin/bash
# Copyright 2022 VMware, Inc.
# SPDX-License-Identifier: Apache-2.0

source setup-multicast.sh

set -e

CLASS_PATH="/toroni/java/toroni/target/toroni-1.0.jar:/toroni/java/system_tests/target/classes:$HOME/.m2/repository/net/java/dev/jna/jna-platform/5.12.1/jna-platform-5.12.1.jar:$HOME/.m2/repository/net/java/dev/jna/jna/5.12.1/jna-5.12.1.jar"
SYSTEM_TESTS_ROOT=/toroni/cpp/system_tests

export TORONI_AGENT_RINGBUF_SIZE_KB=64
export TORONI_AGENT_MESSAGE_SIZE_BYTES=5
export TORONI_AGENT_BACKPRESSURE_SLEEP_MS=15
export TORONI_AGENT_EXTRESULT=1
export TORONI_AGENT_ITERATIONS=1
export TORONI_AGENT_TEST_FLAVOR=FIRST_LAST_DURATION
export TORONI_AGENT_MESSAGES_PER_WRITER=1000

# shared memory initialization compare
java -cp $CLASS_PATH com.vmware.toroni.system_tests.Agent init
cp -rf /dev/shm/toroni* .
/cpp-burst/agent init
diff /dev/shm/toroni-burst-rb toroni-burst-rb
diff /dev/shm/toroni-burst-ri toroni-burst-ri
diff /dev/shm/toroni-burst-s  toroni-burst-s

# java initialization, cpp reader, cpp writer
export TORONI_AGENT_DEFAULT="java -cp $CLASS_PATH com.vmware.toroni.system_tests.Agent"
export TORONI_AGENT_READER="/cpp-burst/agent"
export TORONI_AGENT_WRITER=$TORONI_AGENT_READER
(cd $SYSTEM_TESTS_ROOT/burst && ./bench-cell.sh 1 1 stat)

# cpp initialization, java reader, cpp writer
export TORONI_AGENT_DEFAULT="/cpp-burst/agent"
export TORONI_AGENT_READER="java -cp $CLASS_PATH com.vmware.toroni.system_tests.Agent"
export TORONI_AGENT_WRITER=$TORONI_AGENT_DEFAULT
(cd $SYSTEM_TESTS_ROOT/burst && ./bench-cell.sh 1 1 stat)

# cpp initialization, cpp reader, java writer
export TORONI_AGENT_DEFAULT="/cpp-burst/agent"
export TORONI_AGENT_READER=$TORONI_AGENT_DEFAULT
export TORONI_AGENT_WRITER="java -cp $CLASS_PATH com.vmware.toroni.system_tests.Agent"
(cd $SYSTEM_TESTS_ROOT/burst && ./bench-cell.sh 2 2 stat)

# java initialization, java reader, cpp writer
export TORONI_AGENT_DEFAULT="java -cp $CLASS_PATH com.vmware.toroni.system_tests.Agent"
export TORONI_AGENT_READER=$TORONI_AGENT_DEFAULT
export TORONI_AGENT_WRITER="/cpp-burst/agent"
(cd $SYSTEM_TESTS_ROOT/burst && ./bench-cell.sh 2 2 stat)