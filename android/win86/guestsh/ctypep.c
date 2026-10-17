/* ctypep.c - probe glibc ctype classification under the runtime: prints
 * isalpha/isalnum results for a..z 0..9 plus __ctype_b_loc pointer sanity. */
#include <stdio.h>
#include <ctype.h>

int main(void) {
    fprintf(stderr, "[ctypep] isalnum a..z: ");
    for (int c = 'a'; c <= 'z'; c++)
        fprintf(stderr, "%c", isalnum(c) ? 'y' : 'n');
    fprintf(stderr, "\n[ctypep] isalpha a..z:  ");
    for (int c = 'a'; c <= 'z'; c++)
        fprintf(stderr, "%c", isalpha(c) ? 'y' : 'n');
    fprintf(stderr, "\n[ctypep] isalnum 0..9:  ");
    for (int c = '0'; c <= '9'; c++)
        fprintf(stderr, "%c", isalnum(c) ? 'y' : 'n');
    fprintf(stderr, "\n");
    return 0;
}
