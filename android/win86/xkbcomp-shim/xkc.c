/* Function forms used by the box64-safe stdio shim for xkbcomp. */
#include <stdio.h>

int xkc_fgetc(FILE *f) { return fgetc(f); }
int xkc_fputc(int c, FILE *f) { return fputc(c, f); }
