/* catfR.c - guest probe: reads stdin via fread (like xkbcomp's scanner does)
 * until EOF, prints byte count and first 48 bytes to stderr. */
#include <stdio.h>
#include <string.h>

int main(void) {
    char buf[512];
    size_t total = 0;
    char head[49] = {0};
    size_t hlen = 0;
    for (;;) {
        size_t n = fread(buf, 1, sizeof(buf), stdin);
        if (n == 0)
            break;
        if (hlen < 48) {
            size_t c = 48 - hlen;
            if (n < c) c = n;
            memcpy(head + hlen, buf, c);
            hlen += c;
        }
        total += n;
    }
    if (ferror(stdin)) fprintf(stderr, "[catfR] ferror\n");
    fprintf(stderr, "[catfR] bytes=%zu head=%.48s|\n", total, head);
    return 0;
}
