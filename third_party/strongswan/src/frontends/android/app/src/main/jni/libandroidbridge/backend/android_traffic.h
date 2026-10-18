/* Per-connection plaintext IP byte counters at the Android TUN boundary.
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
#ifndef ANDROID_TRAFFIC_H_
#define ANDROID_TRAFFIC_H_

#include <stdint.h>

/* Called only after the old Android service has been destroyed. */
void android_traffic_reset(void);
void android_traffic_add_download(uint64_t bytes);
void android_traffic_add_upload(uint64_t bytes);
/* Static storage allows reads during native daemon teardown, without SA pointers. */
void android_traffic_snapshot(uint64_t *download, uint64_t *upload);

#endif
