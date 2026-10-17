/* catf.c - guest probe: reads its stdin until EOF, prints how many bytes
 * arrived and the first 48 bytes, to our stderr. Ignores argv. */
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    char buf[8192];
    ssize_t n = read(0, buf, sizeof(buf));
    if (n < 0) { fprintf(stderr, "[catf] read_err\n"); return 3; }
    fprintf(stderr, "[catf] bytes=%zd\n", n);
    fprintf(stderr, "[catf] head=%.48s|\n", buf);
    return 0;
}
