#!/bin/bash
# Copyright 2022 VMware, Inc.
# SPDX-License-Identifier: Apache-2.0

# THIS TEST HAS MUCH LOWER LIMITS IN ORDER TO PASS. SEE CPP FOR ACTUAL LIMITS.
# Transfer 70MB 7*500*20K over 64KB ring buffer and 16 readers
export TORONI_AGENT_RINGBUF_SIZE_KB=64
export TORONI_AGENT_MESSAGE_SIZE_BYTES=10
export TORONI_AGENT_BACKPRESSURE_SLEEP_MS=5 # With smaller sleep readers usually expire
export TORONI_AGENT_EXTRESULT=1
export TORONI_AGENT_TEST_FLAVOR=LATENCY

(source ./setup-agent.sh && cd $SYSTEM_TESTS_ROOT/burst && ./bench-cell.sh 7 7 result 1)