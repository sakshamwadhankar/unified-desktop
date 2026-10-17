/* catfS.c - guest probe: reads stdin via stdio (fgets) until EOF, prints byte
 * count and the first 64 bytes to stderr. Decides box64 stdio-read vs raw-read
 * on the same pipe the real server uses. */
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    char buf[4096];
    size_t total = 0;
    char head[65] = {0};
    size_t hlen = 0;
    int lines = 0;
    while (fgets(buf, sizeof(buf), stdin) != NULL) {
        size_t n = strlen(buf);
        if (hlen < 64) {
            size_t c = 64 - hlen;
            if (n < c) c = n;
            memcpy(head + hlen, buf, c);
            hlen += c;
        }
        total += n;
        lines++;
    }
    if (ferror(stdin)) fprintf(stderr, "[catfS] ferror\n");
    fprintf(stderr, "[catfS] bytes=%zu lines=%d head=%.64s|\n", total, lines, head);
    return 0;
}
