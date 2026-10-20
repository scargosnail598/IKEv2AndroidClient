#include <assert.h>
#include <fcntl.h>
#include <stdio.h>
#include "android_tun_update.h"
int main(void)
{
    int first[2], next[2];
    assert(pipe(first) == 0 && pipe(next) == 0);
    int current = first[0];
    assert(!android_tun_replace(&current, -1));
    assert(current == first[0] && fcntl(current, F_GETFD) >= 0);
    assert(android_tun_replace(&current, next[0]));
    assert(current == next[0] && fcntl(first[0], F_GETFD) < 0);
    assert(fcntl(current, F_GETFD) >= 0);
    char byte = 'x', result = 0;
    assert(write(next[1], &byte, 1) == 1);
    assert(android_tun_read_current(current, first[0], &result, 1) == -1);
    assert(errno == EAGAIN); /* Do not read an old FD number even if another socket reuses it. */
    assert(android_tun_read_current(current, current, &result, 1) == 1 && result == 'x');
    assert(android_tun_replace(&current, current));
    assert(fcntl(current, F_GETFD) >= 0);
    close(current); close(first[1]); close(next[1]);
    assert(pipe(next) == 0);
    current = -1;
    assert(!android_tun_replace(&current, next[0]));
    assert(current == -1 && fcntl(next[0], F_GETFD) < 0);
    close(next[1]);
    puts("Native bypass tests passed: failed rebuild retains TUN, successful swap closes old FD, closed sessions cannot restart.");
    return 0;
}
