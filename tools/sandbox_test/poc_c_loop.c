// POC C：死循环 + 大内存申请（应被墙钟强杀或 rlimit 拦住）
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
int main(void) {
    printf("POC-C start infinite loop\n"); fflush(stdout);
    volatile unsigned long x = 0;
    for (;;) { x++; if ((x & 0xFFFFFFFul) == 0) { printf("POC-C alive %lu\n", x); fflush(stdout); } }
    return 0;
}
