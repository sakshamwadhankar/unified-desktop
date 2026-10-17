/*
 * unified-desktop: minimal functional libfontconfig.so.1 (x86-64, no deps) for the wine rootfs.
 *
 * B-022: wine's win32u (freetype.c) resolves 26 Fc* entry points via dlsym(). The real
 * libfontconfig cannot be loaded by box64 on this device, so every pointer stays NULL -
 * but wine still runs the fontconfig paths (font.c enum_fallbacks -> index>=3 ->
 * freetype.c fontconfig_enum_family_fallbacks -> create_family_pattern ->
 * `call *pFcPatternCreate` == call to NULL), segfaults, and NtUserInitializeClientPfnArrays
 * fails with c0000005, so user32/win32u never initialize. An EMPTY stub is not enough:
 * dlopen succeeds, dlsym still fails, the same NULL call happens (proven on the phone).
 *
 * This stub exports every symbol wine resolves, with the semantics of fontconfig that
 * sees no configuration and no fonts: every pattern matches nothing, no font dirs, no
 * cache. wine then takes its normal "fontconfig knows no fonts" paths and keeps its
 * builtin fonts and C:\windows\Fonts.
 *
 * Built without libc (no NEEDED entries, no runtime relocations) so box64's emulated
 * dlopen cannot fail on a missing dependency:
 *   clang --target=x86_64-linux-gnu -shared -nostdlib -fPIC -O2 \
 *         -Wl,-soname,libfontconfig.so.1 -o libfontconfig.so.1 stub.c
 *
 * All functions tolerate NULL arguments and any (opaque) object value: wine treats
 * FcPattern/FcConfig/FcStrSet/FcStrList/FcCache as opaque and only uses them through
 * these functions, and reads FcFontSet as its public struct {int nfont; int maxfont;
 * FcPattern **fonts;} - we return an empty one and never free it (FcFontSetDestroy is a
 * no-op, so the static empty set is safe to "destroy").
 */

typedef int FcBool;
typedef int FcResult;
typedef unsigned char FcChar8;

typedef struct _FcPattern FcPattern;
typedef struct _FcConfig FcConfig;
typedef struct _FcStrSet FcStrSet;
typedef struct _FcStrList FcStrList;
typedef struct _FcCache FcCache;

typedef struct _FcFontSet {
    int nfont;
    int maxfont;
    FcPattern **fonts;
} FcFontSet;

#define FcFalse 0
#define FcTrue 1
#define FcResultMatch 0
#define FcResultNoMatch 1

/* Opaque "objects": never dereferenced by wine, only passed back to us. */
static char dummy_object;
#define DUMMY ((void *)&dummy_object)

static FcFontSet empty_fontset; /* zero-initialized: nfont = 0, fonts = NULL */

FcBool FcInit(void) { return FcTrue; }

FcConfig *FcConfigGetCurrent(void) { return 0; }
FcStrList *FcConfigGetFontDirs(FcConfig *config) { return 0; }
FcBool FcConfigSubstitute(FcConfig *config, FcPattern *p, int kind) { return FcTrue; }

FcPattern *FcPatternCreate(void) { return DUMMY; }
void FcPatternDestroy(FcPattern *p) { }
FcBool FcPatternAddString(FcPattern *p, const char *object, const FcChar8 *s) { return FcTrue; }
FcResult FcPatternGetString(FcPattern *p, const char *object, int n, FcChar8 **s)
{
    if (s) *s = 0;
    return FcResultNoMatch;
}
FcResult FcPatternGetInteger(FcPattern *p, const char *object, int n, int *i)
{
    if (i) *i = 0;
    return FcResultNoMatch;
}
FcResult FcPatternGetBool(FcPattern *p, const char *object, int n, FcBool *b)
{
    if (b) *b = FcFalse;
    return FcResultNoMatch;
}
void FcDefaultSubstitute(FcPattern *p) { }

FcFontSet *FcFontMatch(FcConfig *config, FcPattern *p, FcResult *result)
{
    if (result) *result = FcResultNoMatch; /* wine reads *result even when we return NULL */
    return 0;
}
FcFontSet *FcFontList(FcConfig *config, FcPattern *p, void *os) { return &empty_fontset; }
void FcFontSetDestroy(FcFontSet *s) { }

FcCache *FcDirCacheRead(const FcChar8 *dir, FcBool force, FcConfig *config) { return 0; }
void FcDirCacheUnload(FcCache *cache) { }
FcFontSet *FcCacheCopySet(const FcCache *cache) { return 0; }
int FcCacheNumSubdir(const FcCache *cache) { return 0; }
const FcChar8 *FcCacheSubdir(const FcCache *cache, int i) { return 0; }

FcStrSet *FcStrSetCreate(void) { return DUMMY; }
void FcStrSetDestroy(FcStrSet *set) { }
FcBool FcStrSetAdd(FcStrSet *set, const FcChar8 *s) { return FcTrue; }
FcBool FcStrSetMember(FcStrSet *set, const FcChar8 *s) { return FcFalse; }

FcStrList *FcStrListCreate(FcStrSet *set) { return DUMMY; }
void FcStrListDone(FcStrList *list) { }
const FcChar8 *FcStrListNext(FcStrList *list) { return 0; }
