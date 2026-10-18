#include "android_traffic.h"
#include <assert.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>

#define THREADS 8
#define ITERATIONS 20000

static void expect(uint64_t download, uint64_t upload)
{
	uint64_t actual_download, actual_upload;
	android_traffic_snapshot(&actual_download, &actual_upload);
	assert(actual_download == download);
	assert(actual_upload == upload);
}

static void *transfer(void *unused)
{
	(void)unused;
	for (int i = 0; i < ITERATIONS; i++)
	{
		android_traffic_add_download(17);
		android_traffic_add_upload(13);
	}
	return NULL;
}

int main(void)
{
	pthread_t writers[THREADS];
	uint64_t download, upload, previous_download = 0, previous_upload = 0;

	android_traffic_reset();
	expect(0, 0);
	android_traffic_add_upload(350);
	expect(0, 350);
	android_traffic_add_download(900);
	expect(900, 350);
	android_traffic_add_download(UINT64_C(1) << 33);
	android_traffic_add_upload(UINT64_C(1) << 34);
	expect((UINT64_C(1) << 33) + 900, (UINT64_C(1) << 34) + 350);

	android_traffic_add_download(UINT64_MAX);
	android_traffic_add_upload(UINT64_MAX);
	expect(INT64_MAX, INT64_MAX);
	android_traffic_add_download(1);
	android_traffic_add_upload(1);
	expect(INT64_MAX, INT64_MAX);

	/* Reconnect starts a clean session, even after saturated counters. */
	android_traffic_reset();
	expect(0, 0);
	for (int i = 0; i < THREADS; i++) assert(pthread_create(&writers[i], NULL, transfer, NULL) == 0);
	for (int i = 0; i < ITERATIONS; i++)
	{
		android_traffic_snapshot(&download, &upload);
		assert(download >= previous_download);
		assert(upload >= previous_upload);
		previous_download = download;
		previous_upload = upload;
	}
	for (int i = 0; i < THREADS; i++) assert(pthread_join(writers[i], NULL) == 0);
	expect((uint64_t)THREADS * ITERATIONS * 17, (uint64_t)THREADS * ITERATIONS * 13);
	android_traffic_reset();
	expect(0, 0);
	puts("Native traffic tests passed: direction, 64-bit totals, saturation, reset, concurrent snapshots.");
	return 0;
}
