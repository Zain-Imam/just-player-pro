// Android 10+ only lets an app run programs that ship inside its APK, so this small one
// does and loads the real one (Python or QuickJS) from the download in app storage.
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

#define TEXT(x) #x
#define NAME(x) TEXT(x)

int main(int argc, char **argv) {
    const char *lib = getenv(NAME(LIBRARY_ENV));
    void *engine = lib ? dlopen(lib, RTLD_NOW | RTLD_GLOBAL) : NULL;
    if (engine == NULL) {
        fprintf(stderr, "%s: %s\n", NAME(LIBRARY_ENV), lib ? dlerror() : "not set");
        return 127;
    }
    int (*start)(int, char **) = (int (*)(int, char **)) dlsym(engine, NAME(ENTRY));
    if (start == NULL) {
        fprintf(stderr, "%s: %s\n", NAME(ENTRY), dlerror());
        return 127;
    }
    return start(argc, argv);
}
