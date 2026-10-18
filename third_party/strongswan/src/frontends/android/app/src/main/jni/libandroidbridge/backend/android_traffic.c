/* Per-connection plaintext IP byte counters at the Android TUN boundary.
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
#include "android_traffic.h"
#include <pthread.h>

/* A mutex also makes 64-bit reads/writes safe on 32-bit Android ABIs. */
static pthread_mutex_t traffic_lock = PTHREAD_MUTEX_INITIALIZER;
static uint64_t downloaded;
static uint64_t uploaded;

static uint64_t add_saturating(uint64_t value, uint64_t bytes)
{
	/* JNI exposes signed longs. Never wrap to a negative value. */
	return bytes > (uint64_t)INT64_MAX - value ? INT64_MAX : value + bytes;
}

void android_traffic_reset(void)
{
	pthread_mutex_lock(&traffic_lock);
	downloaded = uploaded = 0;
	pthread_mutex_unlock(&traffic_lock);
}

void android_traffic_add_download(uint64_t bytes)
{
	pthread_mutex_lock(&traffic_lock);
	downloaded = add_saturating(downloaded, bytes);
	pthread_mutex_unlock(&traffic_lock);
}

void android_traffic_add_upload(uint64_t bytes)
{
	pthread_mutex_lock(&traffic_lock);
	uploaded = add_saturating(uploaded, bytes);
	pthread_mutex_unlock(&traffic_lock);
}

void android_traffic_snapshot(uint64_t *download, uint64_t *upload)
{
	pthread_mutex_lock(&traffic_lock);
	*download = downloaded;
	*upload = uploaded;
	pthread_mutex_unlock(&traffic_lock);
}
