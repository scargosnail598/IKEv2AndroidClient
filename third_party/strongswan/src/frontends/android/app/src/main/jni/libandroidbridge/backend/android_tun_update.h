/* Replace a live TUN only after a successful rebuild. Caller holds the TUN write lock.
 * This does not touch IKE state, session timing or byte counters. */
#ifndef ANDROID_TUN_UPDATE_H
#define ANDROID_TUN_UPDATE_H
#include <stdbool.h>
#include <unistd.h>
#include <errno.h>
static inline bool android_tun_replace(int *current, int candidate)
{
    if (candidate < 0) { return false; }
    if (*current < 0) { close(candidate); return false; }
    if (*current != candidate) { close(*current); }
    *current = candidate;
    return true;
}
/* The caller holds the read lock; the FD selected earlier may have been replaced. */
static inline ssize_t android_tun_read_current(int current, int expected, void *data, size_t size)
{
    if (current < 0 || current != expected) { errno = EAGAIN; return -1; }
    return read(current, data, size);
}
#endif
