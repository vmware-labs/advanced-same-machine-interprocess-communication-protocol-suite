/*
 * Copyright 2022 VMware, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

package toroni.rmp;

public interface ReadCallback {
  void messageRecieved(byte[] data);
}
